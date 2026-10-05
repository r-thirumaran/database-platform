package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Per-database collector bookkeeping (what {@code GET /databases/{id}/collector-status} reports). */
@Entity
@Table(name = "collector_state")
public class CollectorState {
    @Id @Column(length = 36) private String databaseId;
    private Instant lastDictionaryRun;
    private Instant lastRuntimeRun;
    private Instant lastAuditRun;
    private String lastError;
    @Column(nullable = false) private int tablesSeen;
    @Column(nullable = false) private int routinesSeen;
    @Column(nullable = false) private int sessionsSeen;
    private Instant auditCursor;

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public Instant getLastDictionaryRun() { return lastDictionaryRun; }
    public void setLastDictionaryRun(Instant lastDictionaryRun) { this.lastDictionaryRun = lastDictionaryRun; }
    public Instant getLastRuntimeRun() { return lastRuntimeRun; }
    public void setLastRuntimeRun(Instant lastRuntimeRun) { this.lastRuntimeRun = lastRuntimeRun; }
    public Instant getLastAuditRun() { return lastAuditRun; }
    public void setLastAuditRun(Instant lastAuditRun) { this.lastAuditRun = lastAuditRun; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public int getTablesSeen() { return tablesSeen; }
    public void setTablesSeen(int tablesSeen) { this.tablesSeen = tablesSeen; }
    public int getRoutinesSeen() { return routinesSeen; }
    public void setRoutinesSeen(int routinesSeen) { this.routinesSeen = routinesSeen; }
    public int getSessionsSeen() { return sessionsSeen; }
    public void setSessionsSeen(int sessionsSeen) { this.sessionsSeen = sessionsSeen; }
    public Instant getAuditCursor() { return auditCursor; }
    public void setAuditCursor(Instant auditCursor) { this.auditCursor = auditCursor; }
}
