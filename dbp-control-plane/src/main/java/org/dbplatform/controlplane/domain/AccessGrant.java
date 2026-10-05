package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

@Entity
@Table(name = "access_grant")
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessGrant {
    @Id @Column(length = 36) private String id;
    @NotBlank @Column(nullable = false, length = 36) private String applicationId;
    @NotBlank @Column(nullable = false, length = 36) private String datasourceId;
    private Integer maxLogicalConnections;
    private Integer maxProxyConnections;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.PoolMode poolModeOverride;
    @Column(nullable = false) private boolean readOnly = false;
    @Column(nullable = false) private boolean enabled = true;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public Integer getMaxLogicalConnections() { return maxLogicalConnections; }
    public void setMaxLogicalConnections(Integer v) { this.maxLogicalConnections = v; }
    public Integer getMaxProxyConnections() { return maxProxyConnections; }
    public void setMaxProxyConnections(Integer v) { this.maxProxyConnections = v; }
    public Enums.PoolMode getPoolModeOverride() { return poolModeOverride; }
    public void setPoolModeOverride(Enums.PoolMode v) { this.poolModeOverride = v; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
