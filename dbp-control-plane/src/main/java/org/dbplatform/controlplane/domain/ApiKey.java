package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Application API key: only the sha-256 hash of the secret is stored; the plaintext is returned once. */
@Entity
@Table(name = "api_key")
public class ApiKey {
    @Id @Column(length = 36) private String id;
    @JsonIgnore @Column(nullable = false, length = 36) private String applicationId;
    @Column(nullable = false, length = 16) private String prefix;
    @JsonIgnore @Column(nullable = false, length = 64) private String keyHash;
    private String label;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
