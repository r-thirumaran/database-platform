package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pool_stats_snapshot")
public class PoolStatsSnapshot {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Instant recordedAt;
    private Instant eventTime;
    private String gatewayId;
    private String datasourceName;
    @Column(length = 36) private String datasourceId;
    @Column(length = 36) private String databaseId;
    @Column(length = 20) private String engine;
    @Column(name = "active_connections") private Integer active;
    @Column(name = "idle_connections") private Integer idle;
    @Column(name = "waiting_threads") private Integer waiting;
    @Column(name = "total_connections") private Integer total;
    @Column(name = "max_connections") private Integer max;
    private Integer logicalSessions;
    private Integer pinnedSessions;
    private Long credentialVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public Integer getActive() { return active; }
    public void setActive(Integer active) { this.active = active; }
    public Integer getIdle() { return idle; }
    public void setIdle(Integer idle) { this.idle = idle; }
    public Integer getWaiting() { return waiting; }
    public void setWaiting(Integer waiting) { this.waiting = waiting; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Integer getMax() { return max; }
    public void setMax(Integer max) { this.max = max; }
    public Integer getLogicalSessions() { return logicalSessions; }
    public void setLogicalSessions(Integer logicalSessions) { this.logicalSessions = logicalSessions; }
    public Integer getPinnedSessions() { return pinnedSessions; }
    public void setPinnedSessions(Integer pinnedSessions) { this.pinnedSessions = pinnedSessions; }
    public Long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(Long credentialVersion) { this.credentialVersion = credentialVersion; }
}
