package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Hourly aggregate per (sqlHash, applicationId, databaseId, hour). */
@Entity
@Table(name = "query_stat")
public class QueryStat {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 64) private String sqlHash;
    private String sqlNormalized;
    @Column(length = 20) private String operation;
    @Column(length = 36) private String applicationId;
    @Column(length = 36) private String databaseId;
    private String datasourceName;
    @Column(nullable = false) private Instant bucketStart;
    @Column(nullable = false) private long execCount;
    @Column(nullable = false) private long totalDurationMs;
    @Column(nullable = false) private long maxDurationMs;
    /** JSON array of up to 100 sampled durations (reservoir) used for the p95 estimate. */
    private String durationSamples;
    @Column(nullable = false) private long rowCount;
    @Column(nullable = false) private long errorCount;
    /** JSON array of {tableId, access}. */
    private String tablesJson;
    private Instant lastSeenAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSqlHash() { return sqlHash; }
    public void setSqlHash(String sqlHash) { this.sqlHash = sqlHash; }
    public String getSqlNormalized() { return sqlNormalized; }
    public void setSqlNormalized(String sqlNormalized) { this.sqlNormalized = sqlNormalized; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public Instant getBucketStart() { return bucketStart; }
    public void setBucketStart(Instant bucketStart) { this.bucketStart = bucketStart; }
    public long getExecCount() { return execCount; }
    public void setExecCount(long execCount) { this.execCount = execCount; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }
    public long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
    public String getDurationSamples() { return durationSamples; }
    public void setDurationSamples(String durationSamples) { this.durationSamples = durationSamples; }
    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
    public long getErrorCount() { return errorCount; }
    public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
    public String getTablesJson() { return tablesJson; }
    public void setTablesJson(String tablesJson) { this.tablesJson = tablesJson; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
