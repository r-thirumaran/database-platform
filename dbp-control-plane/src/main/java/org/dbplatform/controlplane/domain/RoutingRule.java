package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "routing_rule")
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingRule {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private int priority = 100;
    @Column(length = 36) private String applicationId;
    private String tag;
    @NotBlank @Column(nullable = false, length = 36) private String databaseId;
    @Column(nullable = false) private boolean readOnly = false;
    @Column(nullable = false) private boolean enabled = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
