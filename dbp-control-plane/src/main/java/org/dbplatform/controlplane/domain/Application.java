package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "application")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Application {
    @Id @Column(length = 36) private String id;
    @NotBlank @Size(max = 100) @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "name may contain letters, digits, '.', '_' and '-'")
    @Column(nullable = false) private String name;
    @Size(max = 200) private String displayName;
    @Column(length = 36) private String teamId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ApplicationKind kind = Enums.ApplicationKind.SERVICE;
    private String description;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.Runtime runtime;
    @Convert(converter = JsonConverters.IdentityRulesConv.class) private IdentityRules identityRules = new IdentityRules();
    @Convert(converter = JsonConverters.StringList.class) private List<String> tags = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public Enums.ApplicationKind getKind() { return kind; }
    public void setKind(Enums.ApplicationKind kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Enums.Runtime getRuntime() { return runtime; }
    public void setRuntime(Enums.Runtime runtime) { this.runtime = runtime; }
    public IdentityRules getIdentityRules() { return identityRules; }
    public void setIdentityRules(IdentityRules identityRules) { this.identityRules = identityRules == null ? new IdentityRules() : identityRules; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
