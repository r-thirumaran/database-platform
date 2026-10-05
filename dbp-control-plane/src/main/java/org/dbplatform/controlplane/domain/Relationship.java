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

/** Application → object relationship derived from telemetry or declared by a human. */
@Entity
@Table(name = "relationship")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Relationship {
    @Id @Column(length = 36) private String id;
    @NotBlank @Column(nullable = false, length = 36) private String applicationId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ObjectType objectType;
    @NotBlank @Column(nullable = false, length = 36) private String objectId;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.RelationshipKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Enums.RelationshipSource source = Enums.RelationshipSource.DECLARED;
    @Column(nullable = false) private long queryCount = 0;
    private Instant lastSeenAt;
    private Instant firstSeenAt;
    @Column(nullable = false) private boolean confirmed = false;
    @Column(length = 36) private String viaRoutineId;
    @Column(nullable = false) private double confidence = 1.0;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public Enums.ObjectType getObjectType() { return objectType; }
    public void setObjectType(Enums.ObjectType objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public Enums.RelationshipKind getKind() { return kind; }
    public void setKind(Enums.RelationshipKind kind) { this.kind = kind; }
    public Enums.RelationshipSource getSource() { return source; }
    public void setSource(Enums.RelationshipSource source) { this.source = source; }
    public long getQueryCount() { return queryCount; }
    public void setQueryCount(long queryCount) { this.queryCount = queryCount; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    public String getViaRoutineId() { return viaRoutineId; }
    public void setViaRoutineId(String viaRoutineId) { this.viaRoutineId = viaRoutineId; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
}
