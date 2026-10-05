package org.dbplatform.controlplane.domain;

import java.util.ArrayList;
import java.util.List;

/** Per-database collector settings (docs/control-plane-api.md §3). */
public class CollectorConfig {
    private boolean enabled = false;
    private int dictionaryIntervalSeconds = 3600;
    private int runtimeIntervalSeconds = 15;
    private List<String> schemas = new ArrayList<>();
    private boolean auditTrail = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDictionaryIntervalSeconds() { return dictionaryIntervalSeconds; }
    public void setDictionaryIntervalSeconds(int v) { this.dictionaryIntervalSeconds = v; }
    public int getRuntimeIntervalSeconds() { return runtimeIntervalSeconds; }
    public void setRuntimeIntervalSeconds(int v) { this.runtimeIntervalSeconds = v; }
    public List<String> getSchemas() { return schemas; }
    public void setSchemas(List<String> schemas) { this.schemas = schemas == null ? new ArrayList<>() : schemas; }
    public boolean isAuditTrail() { return auditTrail; }
    public void setAuditTrail(boolean auditTrail) { this.auditTrail = auditTrail; }
}
