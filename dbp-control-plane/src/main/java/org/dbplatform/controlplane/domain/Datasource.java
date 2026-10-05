package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Logical datasource: the name used in {@code jdbc:dbp://.../<name>} and as proxy service alias. */
@Entity
@Table(name = "datasource")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Datasource {
    @Id @Column(length = 36) private String id;
    @NotBlank @Size(max = 100) @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "name may contain letters, digits, '.', '_' and '-'")
    @Column(nullable = false) private String name;
    @Size(max = 200) private String displayName;
    @Column(length = 36) private String ownerTeamId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.DatasourceState state = Enums.DatasourceState.ACTIVE;
    @Column(length = 36) private String currentDatabaseId;
    @Column(length = 36) private String targetDatabaseId;
    @Convert(converter = JsonConverters.PoolPolicyConv.class) private PoolPolicy poolPolicy = new PoolPolicy();
    @Valid
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "datasource_id", nullable = false)
    @OrderBy("priority ASC")
    private List<RoutingRule> routingRules = new ArrayList<>();
    private String description;
    @Convert(converter = JsonConverters.StringList.class) private List<String> tags = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getOwnerTeamId() { return ownerTeamId; }
    public void setOwnerTeamId(String ownerTeamId) { this.ownerTeamId = ownerTeamId; }
    public Enums.DatasourceState getState() { return state; }
    public void setState(Enums.DatasourceState state) { this.state = state; }
    public String getCurrentDatabaseId() { return currentDatabaseId; }
    public void setCurrentDatabaseId(String v) { this.currentDatabaseId = v; }
    public String getTargetDatabaseId() { return targetDatabaseId; }
    public void setTargetDatabaseId(String v) { this.targetDatabaseId = v; }
    public PoolPolicy getPoolPolicy() { return poolPolicy; }
    public void setPoolPolicy(PoolPolicy poolPolicy) { this.poolPolicy = poolPolicy == null ? new PoolPolicy() : poolPolicy; }
    public List<RoutingRule> getRoutingRules() { return routingRules; }
    public void setRoutingRules(List<RoutingRule> routingRules) { this.routingRules = routingRules == null ? new ArrayList<>() : routingRules; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
