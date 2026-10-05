package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A physical database (Oracle, PostgreSQL or SQL Server). API resource name: {@code Database}. */
@Entity
@Table(name = "database_instance")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseInstance {
    @Id @Column(length = 36) private String id;
    @NotBlank @Size(max = 100) @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "name may contain letters, digits, '.', '_' and '-'")
    @Column(nullable = false) private String name;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Enums.Engine engine;
    @NotBlank @Column(nullable = false) private String host;
    @Min(1) @Max(65535) @Column(nullable = false) private int port;
    /** Oracle service name; PostgreSQL / SQL Server database name. */
    private String serviceName;
    @Column(length = 36) private String credentialId;
    private Integer maxPhysicalConnections;
    @Convert(converter = JsonConverters.StringMap.class) private Map<String, String> jdbcProperties = new LinkedHashMap<>();
    @Convert(converter = JsonConverters.CollectorConfigConv.class) @Column(name = "collector_config") private CollectorConfig collector = new CollectorConfig();
    private String description;
    @Convert(converter = JsonConverters.StringList.class) private List<String> tags = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Enums.Engine getEngine() { return engine; }
    public void setEngine(Enums.Engine engine) { this.engine = engine; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public Integer getMaxPhysicalConnections() { return maxPhysicalConnections; }
    public void setMaxPhysicalConnections(Integer v) { this.maxPhysicalConnections = v; }
    public Map<String, String> getJdbcProperties() { return jdbcProperties; }
    public void setJdbcProperties(Map<String, String> v) { this.jdbcProperties = v == null ? new LinkedHashMap<>() : v; }
    public CollectorConfig getCollector() { return collector; }
    public void setCollector(CollectorConfig collector) { this.collector = collector == null ? new CollectorConfig() : collector; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
