package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Static object → object dependency (data dictionary or declared). */
@Entity
@Table(name = "dependency")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dependency {
    @Id @Column(length = 36) private String id;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ObjectType fromType;
    @NotBlank @Column(nullable = false, length = 36) private String fromId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ObjectType toType;
    @NotBlank @Column(nullable = false, length = 36) private String toId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.DependencyKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.DependencySource source = Enums.DependencySource.DECLARED;
    @Column(nullable = false) private double confidence = 1.0;
    private Instant firstSeenAt;
    private Instant lastSeenAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Enums.ObjectType getFromType() { return fromType; }
    public void setFromType(Enums.ObjectType fromType) { this.fromType = fromType; }
    public String getFromId() { return fromId; }
    public void setFromId(String fromId) { this.fromId = fromId; }
    public Enums.ObjectType getToType() { return toType; }
    public void setToType(Enums.ObjectType toType) { this.toType = toType; }
    public String getToId() { return toId; }
    public void setToId(String toId) { this.toId = toId; }
    public Enums.DependencyKind getKind() { return kind; }
    public void setKind(Enums.DependencyKind kind) { this.kind = kind; }
    public Enums.DependencySource getSource() { return source; }
    public void setSource(Enums.DependencySource source) { this.source = source; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
