package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Snapshot of one gateway connection pool. Field names follow {@code docs/telemetry-events.md} exactly. */
public record PoolStats(
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("gatewayId") String gatewayId,
        @JsonProperty("datasource") String datasource,
        @JsonProperty("datasourceId") String datasourceId,
        @JsonProperty("databaseId") String databaseId,
        @JsonProperty("engine") Engine engine,
        @JsonProperty("active") int active,
        @JsonProperty("idle") int idle,
        @JsonProperty("waiting") int waiting,
        @JsonProperty("total") int total,
        @JsonProperty("max") int max,
        @JsonProperty("logicalSessions") int logicalSessions,
        @JsonProperty("pinnedSessions") int pinnedSessions,
        @JsonProperty("credentialVersion") int credentialVersion) {
}
