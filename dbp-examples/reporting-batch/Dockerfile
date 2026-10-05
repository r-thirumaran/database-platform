# syntax=docker/dockerfile:1
# Build context is the repository root:
#   docker build -f dbp-examples/reporting-batch/Dockerfile -t dbp/reporting-batch .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl dbp-examples/reporting-batch -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/dbp-examples/reporting-batch/target/dbp-examples-reporting-batch-*-all.jar /app/app.jar
COPY --chmod=755 dbp-examples/reporting-batch/docker-entrypoint.sh /app/docker-entrypoint.sh
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# one-shot job: no HEALTHCHECK, exit code 0 = ran, 1 = could not connect, 2 = bad configuration
ENTRYPOINT ["/app/docker-entrypoint.sh"]
