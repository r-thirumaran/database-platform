package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Policy {
    @Id @Column(length = 36) private String id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private Enums.PolicyKind kind;
    @Column(nullable = false) private boolean enabled = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Enums.Severity severity = Enums.Severity.MEDIUM;
    private String description;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Enums.PolicyKind getKind() { return kind; }
    public void setKind(Enums.PolicyKind kind) { this.kind = kind; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Enums.Severity getSeverity() { return severity; }
    public void setSeverity(Enums.Severity severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
