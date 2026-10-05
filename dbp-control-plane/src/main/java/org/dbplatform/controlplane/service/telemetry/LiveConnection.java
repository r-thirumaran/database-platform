package org.dbplatform.controlplane.service.telemetry;

import java.time.Instant;

/** One row of {@code GET /connections/live} (proxy snapshot or collector session sample). */
public record LiveConnection(String source, String application, String team, String datasource, String database, String engine,
                             String clientAddr, String program, String machine, String osUser, String dbUser, String status,
                             Instant openedAt, Long durationSeconds, String sqlId, String currentSql,
                             String applicationId, String databaseId, String datasourceId, Integer proxyLocalPort) {
}
