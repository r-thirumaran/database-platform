package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "violation")
public class Violation {
    @Id @Column(length = 36) private String id;
    @JsonIgnore @Column(nullable = false, length = 64) private String fingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private Enums.PolicyKind policyKind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Enums.Severity severity;
    @Column(length = 36) private String applicationId;
    @Column(length = 36) private String teamId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.ObjectType objectType;
    @Column(length = 36) private String objectId;
    private String label;
    private String detail;
    @Column(nullable = false) private Instant firstSeenAt;
    @Column(nullable = false) private Instant lastSeenAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ViolationStatus status = Enums.ViolationStatus.OPEN;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Enums.PolicyKind getPolicyKind() { return policyKind; }
    public void setPolicyKind(Enums.PolicyKind policyKind) { this.policyKind = policyKind; }
    public Enums.Severity getSeverity() { return severity; }
    public void setSeverity(Enums.Severity severity) { this.severity = severity; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public Enums.ObjectType getObjectType() { return objectType; }
    public void setObjectType(Enums.ObjectType objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Enums.ViolationStatus getStatus() { return status; }
    public void setStatus(Enums.ViolationStatus status) { this.status = status; }
}
