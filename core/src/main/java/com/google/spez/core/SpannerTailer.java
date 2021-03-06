/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.spez.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auto.value.AutoValue;
import com.google.cloud.Timestamp;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.*;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.spannerclient.Database;
import com.google.spannerclient.Options;
import com.google.spannerclient.Query;
import com.google.spannerclient.QueryOptions;
import com.google.spannerclient.Row;
import com.google.spannerclient.RowCursor;
import com.google.spannerclient.Spanner;
import com.google.spez.core.SpannerToAvro.SchemaSet;
import com.lmax.disruptor.RingBuffer;
import io.grpc.stub.StreamObserver;
import io.opencensus.common.Scope;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.stats.View;
import io.opencensus.stats.ViewData;
import io.opencensus.stats.ViewManager;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class creates and schedules the spanner event tailer
 *
 * <p>The {@code SpannerTailer} class creates your tailer and schedules it to tail the configured
 * spanner table based on your configuration options. For each row the tailer receives, it will
 * allow you to create a corresponding avro record and publish that record to the configured pub /
 * sub topic. This class assumes you have your Google Cloud credentials set up as described in the
 * {@code README.md} for this git repo.
 *
 * <p>As the tailer tails on a fixed interval, there is no mechanism for retry as the poller will
 * just process the records on its next poll attempt based on the last processed timestamp.
 *
 * <p>For the first run, the poller will try to configure the lastTimestamp based on the last record
 * published to the configured pub / sub topic and updated to the metadata Cloud Spanner table via
 * the lastProcessedTimestamp Cloud Function (if deployed).
 */
public class SpannerTailer {
  private static final Logger log = LoggerFactory.getLogger(SpannerTailer.class);

  private static final Tagger tagger = Tags.getTagger();
  private static final ViewManager viewManager = Stats.getViewManager();
  private static final StatsRecorder statsRecorder = Stats.getStatsRecorder();
  private static final Tracer tracer = Tracing.getTracer();

  // frontendKey allows us to break down the recorded data.
  private static final TagKey TAILER_TABLE_KEY = TagKey.create("spez/keys/tailer-table");

  // videoSize will measure the size of processed videos.
  private static final MeasureLong MESSAGE_SIZE =
      MeasureLong.create("message-size", "Spanner message size", "By");

  private static final long MiB = 1 << 20;

  // Create view to see the processed message size distribution broken down by table.
  // The view has bucket boundaries (0, 16 * MiB, 65536 * MiB) that will group measure
  // values into histogram buckets.
  private static final View.Name MESSAGE_SIZE_VIEW_NAME =
      View.Name.create("spez/views/message-size");
  private static final View MESSAGE_SIZE_VIEW =
      View.create(
          MESSAGE_SIZE_VIEW_NAME,
          "processed message size over time",
          MESSAGE_SIZE,
          Aggregation.Distribution.create(
              BucketBoundaries.create(Arrays.asList(0.0, 16.0 * MiB, 256.0 * MiB))),
          Collections.singletonList(TAILER_TABLE_KEY));

  static {
    viewManager.registerView(MESSAGE_SIZE_VIEW);
  }

  private static final ImmutableList<String> DEFAULT_SERVICE_SCOPES =
      ImmutableList.<String>builder()
          .add("https://www.googleapis.com/auth/cloud-platform")
          .add("https://www.googleapis.com/auth/spanner.data")
          .build();

  private final ListeningExecutorService service;
  private final ScheduledExecutorService scheduler;
  private final HashFunction hasher;
  private final Funnel<Event> eventFunnel;
  private final BloomFilter<Event> bloomFilter;
  private final Map<HashCode, Timestamp> eventMap;

  // We should set an official Spez epoch
  private String lastProcessedTimestamp = "2019-08-08T20:30:39.802644Z";
  private boolean firstRun = true;
  private RingBuffer<SpannerEvent> ringBuffer;

  private final GoogleCredentials credentials;
  private Database database;
  private final AtomicLong running = new AtomicLong(0);

  public SpannerTailer(int threadPool, int maxEventCount) {
    this.scheduler = Executors.newScheduledThreadPool(threadPool);
    this.service =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                threadPool,
                new ThreadFactoryBuilder().setNameFormat("SpannerTailer Acceptor").build()));
    this.hasher = Hashing.murmur3_128();
    this.eventFunnel =
        (Funnel<Event>)
            (event, into) ->
                into.putString(event.uuid(), Charsets.UTF_8)
                    .putString(event.timestamp().toString(), Charsets.UTF_8);

    this.bloomFilter = BloomFilter.create(eventFunnel, maxEventCount, 0.01);
    this.eventMap = new ConcurrentHashMap<>(maxEventCount);

    this.credentials = getCreds();
  }

  private GoogleCredentials getCreds() {
    try {
      return GoogleCredentials.getApplicationDefault().createScoped(DEFAULT_SERVICE_SCOPES);
    } catch (IOException e) {
      log.error("Could not find or parse credential file", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Parses the schema from the Cloud Spanner table and creates an Avro {@code SchemSet} to enable
   * you to dynamically serialize Cloud Spanner events as an Avro record for publshing to an event
   * ledger.
   *
   * @param projectId GCP Project Id
   * @param instanceName Cloud Spanner Instance Name
   * @param dbName Cloud Spanner Database Name
   * @param tableName Cloud Spanner Table Name
   */
  public ListenableFuture<SchemaSet> getSchema(
      String projectId, String instanceName, String dbName, String tableName) {
    Preconditions.checkNotNull(projectId);
    Preconditions.checkNotNull(instanceName);
    Preconditions.checkNotNull(dbName);
    Preconditions.checkNotNull(tableName);

    final String databasePath =
        String.format("projects/%s/instances/test-db/databases/test", projectId);
    final String tsQuery =
        "SELECT * FROM INFORMATION_SCHEMA.COLUMN_OPTIONS WHERE TABLE_NAME = '" + tableName + "'";
    final String pkQuery =
        "SELECT * FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE TABLE_NAME = '" + tableName + "'";
    final String schemaQuery =
        "SELECT COLUMN_NAME, SPANNER_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='"
            + tableName
            + "' ORDER BY ORDINAL_POSITION";

    final SettableFuture<SchemaSet> result = SettableFuture.create();
    final ListenableFuture<Database> dbFuture =
        Spanner.openDatabaseAsync(Options.DEFAULT(), databasePath, credentials);

    final AsyncFunction<Database, List<RowCursor>> querySchemaFuture =
        new AsyncFunction<Database, List<RowCursor>>() {

          @Override
          public ListenableFuture<List<RowCursor>> apply(Database db) throws Exception {
            ImmutableList<ListenableFuture<RowCursor>> fl =
                ImmutableList.of(
                    Spanner.executeAsync(QueryOptions.DEFAULT(), db, Query.create(schemaQuery)),
                    Spanner.executeAsync(QueryOptions.DEFAULT(), db, Query.create(pkQuery)),
                    Spanner.executeAsync(QueryOptions.DEFAULT(), db, Query.create(tsQuery)));

            try {
              db.close();
            } catch (IOException e) {
              log.error("Error Closing Managed Channel", e);
            }

            return Futures.successfulAsList(fl);
          }
        };

    final ListenableFuture<List<RowCursor>> rowCursorsFuture =
        Futures.transformAsync(dbFuture, querySchemaFuture, service);

    final AsyncFunction<List<RowCursor>, SpannerToAvro.SchemaSet> schemaSetFunction =
        new AsyncFunction<List<RowCursor>, SpannerToAvro.SchemaSet>() {

          @Override
          public ListenableFuture<SchemaSet> apply(List<RowCursor> rowCursors) throws Exception {
            final RowCursor rc = rowCursors.get(2);
            while (rc.next()) {
              if (rc.getString("OPTION_NAME").equals("allow_commit_timestamp")) {
                if (rc.getString("OPTION_VALUE").equals("TRUE")) {
                  return SpannerToAvro.GetSchemaAsync(
                      "test", "avroNamespace", rowCursors.get(0), rc.getString("COLUMN_NAME"));
                }
              }
            }

            // TODO(xjdr): Should make custom exception types
            throw new InvalidObjectException("Spanner Table Must contan Commit_Timestamp");
          }
        };

    return Futures.transformAsync(rowCursorsFuture, schemaSetFunction, service);
  }

  private boolean uniq(Event e) {
    final HashCode eventHashCode = hasher.newHasher().putObject(e, eventFunnel).hash();

    if (bloomFilter.mightContain(e)) {
      if (eventMap.putIfAbsent(eventHashCode, e.timestamp()) == null) {
        bloomFilter.put(e);

        return true;
      }
    } else {
      bloomFilter.put(e);
      eventMap.put(eventHashCode, e.timestamp());

      return true;
    }

    return false;
  }

  /**
   * Starts the tailing process by scheduling a query at a configurable fixed delay with a read only
   * query. To maintain state to allow only new events to be processed, a {@code Timestamp} field is
   * required in the table and will be maintained here. As of now, that field is hardcoded as
   * `Timestamp`.
   *
   * @param handler SpannerEventHandler to be triggered on each new record for processing
   * @param tsColName the name of the column holding the true time commit timestamp for processing
   * @param bucketSize Size of the consistent hashing algorythm for thread poll managment
   * @param threadCount xx
   * @param pollRate Time delay in Milliseconds between polls
   * @param projectId GCP Project ID
   * @param instanceName Cloud Spanner Instance Name
   * @param dbName Cloud Spanner Database Name
   * @param tableName Cloud Spanner table Name
   * @param lptsTableName Cloud Spanner table name for lastProcessedTimestamp (as populated by the
   *     lastProcessedTimestamp Cloud Function
   * @param recordLimit The limit for the max number of records returned for a given query
   * @param vacuumRate xxx
   * @param eventCacheTTL xxx
   */
  public ScheduledFuture<?> start(
      SpannerEventHandler handler,
      String tsColName,
      int bucketSize,
      int threadCount,
      int pollRate,
      String projectId,
      String instanceName,
      String dbName,
      String tableName,
      String lptsTableName,
      String recordLimit,
      int vacuumRate,
      long eventCacheTTL) {

    Preconditions.checkNotNull(handler);
    Preconditions.checkNotNull(tsColName);
    Preconditions.checkArgument(bucketSize > 0);
    Preconditions.checkArgument(threadCount > 0);
    Preconditions.checkArgument(pollRate > 0);
    Preconditions.checkNotNull(projectId);
    Preconditions.checkNotNull(instanceName);
    Preconditions.checkNotNull(dbName);
    Preconditions.checkNotNull(tableName);
    Preconditions.checkNotNull(lptsTableName);
    Preconditions.checkNotNull(recordLimit);
    Preconditions.checkArgument(vacuumRate > 0);
    Preconditions.checkArgument(eventCacheTTL > 0);

    final String databasePath =
        String.format("projects/%s/instances/test-db/databases/test", projectId);

    log.info("Building database with path '{}'", databasePath);
    final ListenableFuture<Database> dbFuture =
        Spanner.openDatabaseAsync(Options.DEFAULT(), databasePath, credentials);

    Futures.addCallback(
        dbFuture,
        new FutureCallback<Database>() {
          @Override
          public void onSuccess(Database db) {
            SpannerTailer.this.database = db;
            log.info("Built database, starting scheduler");
            try {
              final ScheduledFuture<?> poller =
                  scheduler.scheduleAtFixedRate(
                      () -> {
                        poll(
                            projectId,
                            instanceName,
                            dbName,
                            tableName,
                            lptsTableName,
                            recordLimit,
                            handler,
                            tsColName,
                            bucketSize);
                      },
                      0,
                      30, // TODO(pdex): I should be a runtime option
                      TimeUnit.SECONDS);
              // return poller;

            } catch (Exception e) {
              log.error("Coudln't start poller", e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            log.error("Failed to acquire a database: ", t);
          }
        },
        service);

    // TODO(xjdr): Don't do this.
    return null;
  }

  private void poll(
      String projectId,
      String instanceName,
      String dbName,
      String tableName,
      String lptsTableName,
      String recordLimit,
      SpannerEventHandler handler,
      String tsColName,
      int bucketSize) {

    long num = running.incrementAndGet();
    if (num > 1) {
      log.debug("Already {} polling processes in flight", num - 1);
      running.decrementAndGet();
      return;
    }
    log.debug("POLLER ACTIVE");
    try {
      if (firstRun) {

        final String tsQuery =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMN_OPTIONS WHERE TABLE_NAME = '"
                + lptsTableName
                + "'";

        // RowCursor lptsColNameCursor =
        //     Spanner.execute(QueryOptions.DEFAULT(), db, Query.create(tsQuery));

        RowCursor lptsCursor =
            Spanner.execute(
                QueryOptions.DEFAULT(), database, Query.create("SELECT * FROM " + lptsTableName));

        // while (lptsColNameCursor.next()) {
        //   if
        // (lptsColNameCursor.getString("OPTION_NAME").equals("allow_commit_timestamp")) {
        //     if (lptsColNameCursor.getString("OPTION_VALUE").equals("TRUE")) {
        while (lptsCursor.next()) {
          String startingTimestamp =
              lptsCursor.getString(
                  "LastProcessedTimestamp"); // lptsColNameCursor.getString("COLUMN_NAME"));
          Timestamp tt = Timestamp.parseTimestamp(startingTimestamp);
          lastProcessedTimestamp = tt.toString();
        }
        //     }
        //   }
        // }

        firstRun = false;
        // lastProcessedTimestamp = "2020-02-06T23:57:58.602900Z";
      }

      log.info("Polling for records newer than {}", lastProcessedTimestamp);
      Instant then = Instant.now();
      AtomicLong records = new AtomicLong(0);
      Spanner.executeStreaming(
          QueryOptions.newBuilder().setReadOnly(true).setStale(true).setMaxStaleness(500).build(),
          database,
          new StreamObserver<Row>() {
            @Override
            public void onNext(Row row) {
              long count = records.incrementAndGet();
              log.debug("onNext count = {}", count);
              ListenableFuture<Boolean> x =
                  service.submit(
                      () -> {
                        try (Scope scopedTags =
                            tagger
                                .currentBuilder()
                                .put(TAILER_TABLE_KEY, TagValue.create(tableName))
                                .buildScoped()) {
                          processRow(handler, row, tsColName);
                          lastProcessedTimestamp = row.getTimestamp(tsColName).toString();
                          statsRecorder.newMeasureMap().put(MESSAGE_SIZE, row.getSize()).record();
                          return Boolean.TRUE;
                        }
                      });

              Futures.addCallback(
                  x,
                  new FutureCallback<Boolean>() {

                    @Override
                    public void onSuccess(Boolean result) {
                      log.debug("Row Successfully Processed");
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      log.error("Unable to Process Row", t);
                    }
                  },
                  service);
            }

            @Override
            public void onError(Throwable t) {
              log.error("Poller", t);
              running.decrementAndGet();
            }

            @Override
            public void onCompleted() {
              Instant now = Instant.now();
              Duration duration = Duration.between(then, now);
              log.debug("SpannerTailer completed!");
              log.warn(
                  "Processed {} records in {} seconds for LPTS {}",
                  records.get(),
                  duration.toNanos() / 1000000000.0,
                  lastProcessedTimestamp);
              running.decrementAndGet();
            }
          },
          Query.create(
              "SELECT * FROM "
                  + tableName
                  + " "
                  + "WHERE Timestamp > '"
                  + lastProcessedTimestamp
                  + "'"));
    } catch (Exception e) {
      log.error("Caught error while polling", e);
      running.decrementAndGet();
    }
  }

  public void close() {
    try {
      if (database != null) {
        database.close();
      }
    } catch (IOException e) {
      log.error("Error Closing Managed Channel", e);
    }
  }

  private Boolean processRow(SpannerEventHandler handler, Row row, String tsColName) {
    final String uuid = Long.toString(row.getLong(0));
    final Timestamp ts = row.getTimestamp(tsColName);
    final Event e = Event.create(uuid, ts);

    // if (uniq(e)) {
    final HashCode sortingKeyHashCode = hasher.newHasher().putBytes(uuid.getBytes(UTF_8)).hash();
    final int bucket = Hashing.consistentHash(sortingKeyHashCode, 12);

    handler.process(bucket, row, ts.toString());
    lastProcessedTimestamp = ts.toString();
    // }

    return true;
  }

  public void logStats() {
    ViewData viewData = viewManager.getView(MESSAGE_SIZE_VIEW_NAME);
    log.info(
        String.format("Recorded stats for %s:\n %s", MESSAGE_SIZE_VIEW_NAME.asString(), viewData));
  }

  public void setRingBuffer(RingBuffer<SpannerEvent> ringBuffer) {
    this.ringBuffer = ringBuffer;
  }

  /**
   * Starts the tailing process by scheduling a query at a configurable fixed delay with a read only
   * query. To maintain state to allow only new events to be processed, a {@code Timestamp} field is
   * required in the table and will be maintained here. As of now, that field is hardcoded as
   * `Timestamp`.
   *
   * @param threadCount xx
   * @param pollRate Time delay in Milliseconds between polls
   * @param projectId GCP Project ID
   * @param instanceName Cloud Spanner Instance Name
   * @param dbName Cloud Spanner Database Name
   * @param tableName Cloud Spanner table Name
   * @param lptsTableName Cloud Spanner table name for lastProcessedTimestamp (as populated by the
   *     lastProcessedTimestamp Cloud Function
   * @param tsColName the name of the column holding the true time commit timestamp for processing
   * @param recordLimit The limit for the max number of records returned for a given query
   */
  public ScheduledFuture<?> start(
      int threadCount,
      int pollRate,
      String projectId,
      String instanceName,
      String dbName,
      String tableName,
      String lptsTableName,
      String tsColName,
      String recordLimit) {

    Preconditions.checkArgument(threadCount > 0);
    Preconditions.checkArgument(pollRate > 0);
    Preconditions.checkNotNull(projectId);
    Preconditions.checkNotNull(instanceName);
    Preconditions.checkNotNull(dbName);
    Preconditions.checkNotNull(tableName);
    Preconditions.checkNotNull(lptsTableName);
    Preconditions.checkNotNull(tsColName);
    Preconditions.checkNotNull(recordLimit);

    try {
      final ScheduledFuture<?> poller =
          scheduler.scheduleAtFixedRate(
              () -> {
                poll(
                    projectId,
                    instanceName,
                    dbName,
                    tableName,
                    lptsTableName,
                    tsColName,
                    recordLimit);
              },
              0,
              30,
              TimeUnit.SECONDS);
      return poller;

    } catch (Exception e) {

    }

    // TODO(xjdr): Don't do this.
    return null;
  }

  private void poll(
      String projectId,
      String instanceName,
      String dbName,
      String tableName,
      String lptsTableName,
      String tsColName,
      String recordLimit) {

    final String databasePath =
        String.format("projects/%s/instances/test-db/databases/test", projectId);

    final ListenableFuture<Database> dbFuture =
        Spanner.openDatabaseAsync(Options.DEFAULT(), databasePath, credentials);

    Futures.addCallback(
        dbFuture,
        new FutureCallback<Database>() {

          @Override
          public void onSuccess(Database db) {
            try {
              if (firstRun) {

                final String tsQuery =
                    "SELECT * FROM INFORMATION_SCHEMA.COLUMN_OPTIONS WHERE TABLE_NAME = '"
                        + lptsTableName
                        + "'";

                // RowCursor lptsColNameCursor =
                //     Spanner.execute(QueryOptions.DEFAULT(), db, Query.create(tsQuery));

                RowCursor lptsCursor =
                    Spanner.execute(
                        QueryOptions.DEFAULT(), db, Query.create("SELECT * FROM " + lptsTableName));

                // while (lptsColNameCursor.next()) {
                //   if
                // (lptsColNameCursor.getString("OPTION_NAME").equals("allow_commit_timestamp")) {
                //     if (lptsColNameCursor.getString("OPTION_VALUE").equals("TRUE")) {
                while (lptsCursor.next()) {
                  String startingTimestamp =
                      lptsCursor.getString(
                          "LastProcessedTimestamp"); // lptsColNameCursor.getString("COLUMN_NAME"));
                  Timestamp tt = Timestamp.parseTimestamp(startingTimestamp);
                  lastProcessedTimestamp = tt.toString();
                }
                //     }
                //   }
                // }

                firstRun = false;
              }

              Spanner.executeStreaming(
                  QueryOptions.newBuilder()
                      .setReadOnly(true)
                      .setStale(true)
                      .setMaxStaleness(15)
                      .build(),
                  db,
                  new StreamObserver<Row>() {
                    @Override
                    public void onNext(Row row) {
                      final long seq = ringBuffer.next();
                      try {
                        SpannerEvent event = ringBuffer.get(seq);
                        event.set(row, tsColName);
                      } catch (Exception e) {
                        log.error("Error processing event", e);
                      } finally {
                        ringBuffer.publish(seq);
                        lastProcessedTimestamp = row.getTimestamp(tsColName).toString();
                      }
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                  },
                  Query.create(
                      "SELECT * FROM "
                          + tableName
                          + " "
                          + "WHERE Timestamp > '"
                          + lastProcessedTimestamp
                          + "'"));

            } finally {
              try {
                db.close();
              } catch (IOException e) {
                log.error("Error Closing Managed Channel", e);
              }
            }
          }

          @Override
          public void onFailure(Throwable t) {
            log.error("Failure Polling DB: ", t);
          }
        },
        service);
  }

  @AutoValue
  abstract static class Event {
    static Event create(String uuid, Timestamp timestamp) {
      return new AutoValue_SpannerTailer_Event(uuid, timestamp);
    }

    abstract String uuid();

    abstract Timestamp timestamp();
  }
}
