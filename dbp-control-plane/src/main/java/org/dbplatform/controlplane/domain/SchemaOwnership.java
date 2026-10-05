package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Default owner team of every table in a schema; applied to tables discovered later. */
@Entity
@Table(name = "schema_ownership")
public class SchemaOwnership {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String databaseId;
    @Column(name = "schema_name", nullable = false) private String schema;
    @Column(nullable = false, length = 36) private String teamId;
    @Column(nullable = false) private boolean confirmed = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
}
