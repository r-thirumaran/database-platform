package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Raw QueryEvent kept for a bounded retention window; the event id is the idempotency key. */
@Entity
@Table(name = "query_event_raw")
public class QueryEventRaw {
    @Id @Column(length = 64) private String eventId;
    @Column(nullable = false) private Instant receivedAt;
    private Instant eventTime;
    private String gatewayId;
    private String sessionId;
    @Column(length = 36) private String applicationId;
    private String applicationName;
    private String teamName;
    private String datasourceName;
    @Column(length = 36) private String databaseId;
    @Column(length = 20) private String engine;
    @Column(length = 64) private String sqlHash;
    private String sqlNormalized;
    @Column(length = 20) private String operation;
    private String tablesJson;
    private String routinesJson;
    private String columnsJson;
    private Long durationMs;
    private Long rowCount;
    private Boolean success;
    private String sqlState;
    private Integer errorCode;
    private String errorMessage;
    private Boolean pinned;
    private String poolMode;
    private String clientInfo;
    private String defaultSchema;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getSqlHash() { return sqlHash; }
    public void setSqlHash(String sqlHash) { this.sqlHash = sqlHash; }
    public String getSqlNormalized() { return sqlNormalized; }
    public void setSqlNormalized(String sqlNormalized) { this.sqlNormalized = sqlNormalized; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getTablesJson() { return tablesJson; }
    public void setTablesJson(String tablesJson) { this.tablesJson = tablesJson; }
    public String getRoutinesJson() { return routinesJson; }
    public void setRoutinesJson(String routinesJson) { this.routinesJson = routinesJson; }
    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String columnsJson) { this.columnsJson = columnsJson; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getSqlState() { return sqlState; }
    public void setSqlState(String sqlState) { this.sqlState = sqlState; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public String getPoolMode() { return poolMode; }
    public void setPoolMode(String poolMode) { this.poolMode = poolMode; }
    public String getClientInfo() { return clientInfo; }
    public void setClientInfo(String clientInfo) { this.clientInfo = clientInfo; }
    public String getDefaultSchema() { return defaultSchema; }
    public void setDefaultSchema(String defaultSchema) { this.defaultSchema = defaultSchema; }
}
