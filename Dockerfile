# Copyright (C) 2020 Google LLC.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ARG JDK_VERSION=11
FROM openjdk:${JDK_VERSION}-jdk-slim as build-env
# Get gradle distribution
COPY *.gradle gradle.* gradlew /app/
COPY gradle /app/gradle
RUN /app/gradlew --version

FROM build-env as app-build
ADD . /app
WORKDIR /app
USER root
RUN ./gradlew clean :cdc:spannerTailerService && \
  mv cdc/build/libs/Main-fat-*.jar Main.jar

FROM openjdk:${JDK_VERSION}-jdk-stretch as dev
RUN apt-get update && apt-get install -y libgtk-3-bin
COPY --from=app-build /app/Main.jar /app/Main.jar
ENV JVM_HEAP_SIZE=6g
ENV JAVA_TOOL_OPTIONS="-Xmx${JVM_HEAP_SIZE}"
ADD cdc/docker/jvm-arguments /app/
WORKDIR /app
ENTRYPOINT ["java", "@/app/jvm-arguments", "-jar", "Main.jar"]

FROM gcr.io/distroless/java:${JDK_VERSION} as prod
COPY --from=app-build /app/Main.jar /app/Main.jar
ENV JVM_HEAP_SIZE=12g
ENV JAVA_TOOL_OPTIONS="-Xmx${JVM_HEAP_SIZE}"
ADD cdc/docker/jvm-arguments /app/
WORKDIR /app
ENTRYPOINT ["java", "@/app/jvm-arguments", "-jar", "Main.jar"]
