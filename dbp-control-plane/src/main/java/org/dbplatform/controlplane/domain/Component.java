package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A gateway or proxy instance known from heartbeats. */
@Entity
@Table(name = "component")
public class Component {
    @Id @Column(length = 36) private String id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.ComponentType componentType;
    @Column(nullable = false) private String componentId;
    private String version;
    private String host;
    private Instant startedAt;
    @Column(nullable = false) private Instant lastHeartbeat;
    private Long configVersion;
    @JsonIgnore private String statsJson;
    @JsonIgnore private String liveConnectionsJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Enums.ComponentType getComponentType() { return componentType; }
    public void setComponentType(Enums.ComponentType componentType) { this.componentType = componentType; }
    public String getComponentId() { return componentId; }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public String getStatsJson() { return statsJson; }
    public void setStatsJson(String statsJson) { this.statsJson = statsJson; }
    public String getLiveConnectionsJson() { return liveConnectionsJson; }
    public void setLiveConnectionsJson(String liveConnectionsJson) { this.liveConnectionsJson = liveConnectionsJson; }
}
