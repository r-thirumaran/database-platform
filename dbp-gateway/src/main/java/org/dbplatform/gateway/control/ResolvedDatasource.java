package org.dbplatform.gateway.control;

import org.dbplatform.gateway.pool.PoolMode;
import org.dbplatform.gateway.pool.PoolSettings;

/**
 * A datasource as resolved for one application: physical target, pool policy and credential reference (without
 * the secret).
 *
 * @param settings     pool template (username may be filled in later from the credential material)
 * @param datasourceId control plane id (nullable)
 * @param databaseId   control plane id (nullable)
 */
public record ResolvedDatasource(PoolSettings settings, String datasourceId, String databaseId) {

    public String name() {
        return settings.datasourceName();
    }

    public PoolMode poolMode() {
        return settings.poolMode();
    }

    public String credentialId() {
        return settings.credentialId();
    }

    public int credentialVersion() {
        return settings.credentialVersion();
    }
}
