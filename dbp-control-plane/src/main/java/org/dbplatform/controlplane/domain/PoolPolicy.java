package org.dbplatform.controlplane.domain;

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
}
