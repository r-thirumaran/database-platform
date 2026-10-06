package org.dbplatform.controlplane.domain;

import java.util.Objects;

/** Gateway pool policy of a datasource (docs/control-plane-api.md §5). */
public class PoolPolicy {
    private Enums.PoolMode mode = Enums.PoolMode.TRANSACTION;
    private int maxConnections = 20;
    private int minIdle = 1;
    private long connectionTimeoutMs = 10_000;
    private long idleTimeoutMs = 600_000;
    private long maxLifetimeMs = 1_800_000;
    private int statementTimeoutSeconds = 0;
    private String validationQuery;

    public Enums.PoolMode getMode() { return mode; }
    public void setMode(Enums.PoolMode mode) { this.mode = mode == null ? Enums.PoolMode.TRANSACTION : mode; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public void setConnectionTimeoutMs(long v) { this.connectionTimeoutMs = v; }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public void setIdleTimeoutMs(long v) { this.idleTimeoutMs = v; }
    public long getMaxLifetimeMs() { return maxLifetimeMs; }
    public void setMaxLifetimeMs(long v) { this.maxLifetimeMs = v; }
    public int getStatementTimeoutSeconds() { return statementTimeoutSeconds; }
    public void setStatementTimeoutSeconds(int v) { this.statementTimeoutSeconds = v; }
    public String getValidationQuery() { return validationQuery; }
    public void setValidationQuery(String validationQuery) { this.validationQuery = validationQuery; }

    // Value semantics: Hibernate compares the loaded snapshot of a converted attribute with its current value using equals(); without it
    // every flush saw a "changed" attribute and re-wrote the owning row (see SpuriousUpdateTest).
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PoolPolicy other)) return false;
        return Objects.equals(mode, other.mode)
                && maxConnections == other.maxConnections
                && minIdle == other.minIdle
                && connectionTimeoutMs == other.connectionTimeoutMs
                && idleTimeoutMs == other.idleTimeoutMs
                && maxLifetimeMs == other.maxLifetimeMs
                && statementTimeoutSeconds == other.statementTimeoutSeconds
                && Objects.equals(validationQuery, other.validationQuery);
    }

    @Override public int hashCode() { return Objects.hash(mode, maxConnections, minIdle, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, statementTimeoutSeconds, validationQuery); }
}
