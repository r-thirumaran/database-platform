package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Procedure, function, package, trigger or view (views are routines for dependency purposes). */
@Entity
@Table(name = "routine")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Routine {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String databaseId;
    @JsonProperty("schema") @Column(name = "schema_name", nullable = false) private String schema;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.RoutineKind kind = Enums.RoutineKind.PROCEDURE;
    @Column(length = 36) private String triggerTableId;
    private String triggerEvent;
    @Column(length = 36) private String ownerTeamId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.RoutineStatus status = Enums.RoutineStatus.VALID;
    private Instant lastDdlAt;
    private Instant lastSeenAt;
    private Instant firstSeenAt;
    @Column(nullable = false) private boolean discovered = false;

    public String label() { return schema + "." + name; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Enums.RoutineKind getKind() { return kind; }
    public void setKind(Enums.RoutineKind kind) { this.kind = kind; }
    public String getTriggerTableId() { return triggerTableId; }
    public void setTriggerTableId(String triggerTableId) { this.triggerTableId = triggerTableId; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getOwnerTeamId() { return ownerTeamId; }
    public void setOwnerTeamId(String ownerTeamId) { this.ownerTeamId = ownerTeamId; }
    public Enums.RoutineStatus getStatus() { return status; }
    public void setStatus(Enums.RoutineStatus status) { this.status = status; }
    public Instant getLastDdlAt() { return lastDdlAt; }
    public void setLastDdlAt(Instant lastDdlAt) { this.lastDdlAt = lastDdlAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public boolean isDiscovered() { return discovered; }
    public void setDiscovered(boolean discovered) { this.discovered = discovered; }
}
