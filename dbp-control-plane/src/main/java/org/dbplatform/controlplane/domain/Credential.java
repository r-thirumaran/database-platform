package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Credential used by the platform to connect to a physical database. Secret material never leaves the internal API. */
@Entity
@Table(name = "credential")
public class Credential {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private String name;
    private String username;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Enums.CredentialProvider provider = Enums.CredentialProvider.INLINE;
    private String ref;
    @JsonIgnore private String encryptedSecret;
    @Column(nullable = false) private long version = 1;
    private Instant rotatedAt;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Enums.CredentialProvider getProvider() { return provider; }
    public void setProvider(Enums.CredentialProvider provider) { this.provider = provider; }
    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public void setEncryptedSecret(String encryptedSecret) { this.encryptedSecret = encryptedSecret; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
