package org.dbplatform.controlplane.domain;

/** Migration plan of a single table (docs/control-plane-api.md §7). */
public class TableMigration {
    private String targetDatabaseId;
    private String targetSchema;
    private String targetName;
    private Enums.MigrationState state = Enums.MigrationState.NOT_PLANNED;

    public String getTargetDatabaseId() { return targetDatabaseId; }
    public void setTargetDatabaseId(String v) { this.targetDatabaseId = v; }
    public String getTargetSchema() { return targetSchema; }
    public void setTargetSchema(String v) { this.targetSchema = v; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String v) { this.targetName = v; }
    public Enums.MigrationState getState() { return state; }
    public void setState(Enums.MigrationState state) { this.state = state == null ? Enums.MigrationState.NOT_PLANNED : state; }
}
