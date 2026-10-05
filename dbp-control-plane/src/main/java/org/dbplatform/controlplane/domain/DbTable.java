package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Catalogue table / view. API resource name: {@code Table}. */
@Entity
@Table(name = "db_table")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbTable {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String databaseId;
    @JsonProperty("schema") @Column(name = "schema_name", nullable = false) private String schema;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.TableKind kind = Enums.TableKind.TABLE;
    @Column(length = 36) private String ownerTeamId;
    @Column(nullable = false) private boolean ownerConfirmed = false;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.OwnerSource ownerSource = Enums.OwnerSource.NONE;
    @Column(length = 36) private String producerApplicationId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.OwnerSource producerSource;
    private Long rowCountEstimate;
    private Instant lastDdlAt;
    private Instant lastSeenAt;
    private Instant firstSeenAt;
    @Convert(converter = JsonConverters.TableMigrationConv.class) private TableMigration migration = new TableMigration();
    private String description;
    @Convert(converter = JsonConverters.StringList.class) private List<String> tags = new ArrayList<>();
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.Classification classification;
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
    public Enums.TableKind getKind() { return kind; }
    public void setKind(Enums.TableKind kind) { this.kind = kind == null ? Enums.TableKind.TABLE : kind; }
    public String getOwnerTeamId() { return ownerTeamId; }
    public void setOwnerTeamId(String ownerTeamId) { this.ownerTeamId = ownerTeamId; }
    public boolean isOwnerConfirmed() { return ownerConfirmed; }
    public void setOwnerConfirmed(boolean ownerConfirmed) { this.ownerConfirmed = ownerConfirmed; }
    public Enums.OwnerSource getOwnerSource() { return ownerSource; }
    public void setOwnerSource(Enums.OwnerSource ownerSource) { this.ownerSource = ownerSource == null ? Enums.OwnerSource.NONE : ownerSource; }
    public String getProducerApplicationId() { return producerApplicationId; }
    public void setProducerApplicationId(String v) { this.producerApplicationId = v; }
    public Enums.OwnerSource getProducerSource() { return producerSource; }
    public void setProducerSource(Enums.OwnerSource producerSource) { this.producerSource = producerSource; }
    public Long getRowCountEstimate() { return rowCountEstimate; }
    public void setRowCountEstimate(Long rowCountEstimate) { this.rowCountEstimate = rowCountEstimate; }
    public Instant getLastDdlAt() { return lastDdlAt; }
    public void setLastDdlAt(Instant lastDdlAt) { this.lastDdlAt = lastDdlAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public TableMigration getMigration() { return migration; }
    public void setMigration(TableMigration migration) { this.migration = migration == null ? new TableMigration() : migration; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Enums.Classification getClassification() { return classification; }
    public void setClassification(Enums.Classification classification) { this.classification = classification; }
    public boolean isDiscovered() { return discovered; }
    public void setDiscovered(boolean discovered) { this.discovered = discovered; }
}
