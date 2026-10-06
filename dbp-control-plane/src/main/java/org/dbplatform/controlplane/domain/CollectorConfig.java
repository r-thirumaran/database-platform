package org.dbplatform.controlplane.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-database collector settings (docs/control-plane-api.md §3). */
public class CollectorConfig {
    private boolean enabled = false;
    private int dictionaryIntervalSeconds = 3600;
    private int runtimeIntervalSeconds = 15;
    private List<String> schemas = new ArrayList<>();
    private boolean auditTrail = false;
    /** Optional credential used by the collectors instead of the database's platform credential. */
    private String credentialId;

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
    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    // Value semantics: Hibernate compares the loaded snapshot of a converted attribute with its current value using equals(); without it
    // every flush saw a "changed" attribute and re-wrote the owning row (see SpuriousUpdateTest).
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CollectorConfig other)) return false;
        return enabled == other.enabled
                && dictionaryIntervalSeconds == other.dictionaryIntervalSeconds
                && runtimeIntervalSeconds == other.runtimeIntervalSeconds
                && Objects.equals(schemas, other.schemas)
                && auditTrail == other.auditTrail
                && Objects.equals(credentialId, other.credentialId);
    }

    @Override public int hashCode() { return Objects.hash(enabled, dictionaryIntervalSeconds, runtimeIntervalSeconds, schemas, auditTrail, credentialId); }
}
