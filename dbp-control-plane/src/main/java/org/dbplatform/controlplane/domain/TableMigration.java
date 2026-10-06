package org.dbplatform.controlplane.domain;

import java.util.Objects;

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

    // Value semantics: Hibernate compares the loaded snapshot of a converted attribute with its current value using equals(); without it
    // every flush saw a "changed" attribute and re-wrote the owning row (see SpuriousUpdateTest).
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableMigration other)) return false;
        return Objects.equals(targetDatabaseId, other.targetDatabaseId)
                && Objects.equals(targetSchema, other.targetSchema)
                && Objects.equals(targetName, other.targetName)
                && Objects.equals(state, other.state);
    }

    @Override public int hashCode() { return Objects.hash(targetDatabaseId, targetSchema, targetName, state); }
}
