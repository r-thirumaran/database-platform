package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Physical pool sizing policy attached to a datasource resolution. All fields are optional; the gateway
 * applies its own defaults for {@code null} values.
 */
public record PoolPolicy(
        @JsonProperty("minIdle") Integer minIdle,
        @JsonProperty("maxSize") Integer maxSize,
        @JsonProperty("connectionTimeoutMs") Long connectionTimeoutMs,
        @JsonProperty("idleTimeoutMs") Long idleTimeoutMs,
        @JsonProperty("maxLifetimeMs") Long maxLifetimeMs,
        @JsonProperty("validationTimeoutMs") Long validationTimeoutMs,
        @JsonProperty("validationQuery") String validationQuery,
        @JsonProperty("pinTimeoutMs") Long pinTimeoutMs,
        @JsonProperty("statementTimeoutMs") Long statementTimeoutMs) {

    public static PoolPolicy defaults() {
        return new PoolPolicy(2, 20, 5_000L, 600_000L, 1_800_000L, 5_000L, null, 60_000L, null);
    }

    public int minIdleOr(int dflt) { return minIdle == null ? dflt : minIdle; }
    public int maxSizeOr(int dflt) { return maxSize == null ? dflt : maxSize; }
    public long connectionTimeoutMsOr(long dflt) { return connectionTimeoutMs == null ? dflt : connectionTimeoutMs; }
    public long idleTimeoutMsOr(long dflt) { return idleTimeoutMs == null ? dflt : idleTimeoutMs; }
    public long maxLifetimeMsOr(long dflt) { return maxLifetimeMs == null ? dflt : maxLifetimeMs; }
    public long validationTimeoutMsOr(long dflt) { return validationTimeoutMs == null ? dflt : validationTimeoutMs; }
    public long pinTimeoutMsOr(long dflt) { return pinTimeoutMs == null ? dflt : pinTimeoutMs; }
}
