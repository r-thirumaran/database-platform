package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "migration_event")
public class MigrationEvent {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String datasourceId;
    @Column(length = 36) private String fromDatabaseId;
    @Column(length = 36) private String toDatabaseId;
    @JsonProperty("at") @Column(name = "occurred_at", nullable = false) private Instant at;
    @JsonProperty("by") @Column(name = "actor") private String by;
    private String note;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getFromDatabaseId() { return fromDatabaseId; }
    public void setFromDatabaseId(String fromDatabaseId) { this.fromDatabaseId = fromDatabaseId; }
    public String getToDatabaseId() { return toDatabaseId; }
    public void setToDatabaseId(String toDatabaseId) { this.toDatabaseId = toDatabaseId; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String getBy() { return by; }
    public void setBy(String by) { this.by = by; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
