package org.dbplatform.gateway.control;

import org.dbplatform.gateway.pool.PoolMode;

/**
 * Result of resolving a HELLO: who the application is, which physical database serves the datasource and which
 * policies apply to the session.
 *
 * @param identity              application identity
 * @param datasource            resolved datasource (pool template)
 * @param poolMode              effective pool mode (grant override > datasource policy > TRANSACTION)
 * @param readOnly              grant forces read-only physical connections
 * @param maxLogicalConnections cap of logical sessions for this (application, datasource) on this gateway (0 = none)
 * @param configVersion         control plane config version this resolution is based on (-1 in static mode)
 */
public record SessionResolution(Identity identity, ResolvedDatasource datasource, PoolMode poolMode, boolean readOnly,
                                int maxLogicalConnections, long configVersion) {

    /** Key used for the per-grant logical connection cap. */
    public String grantKey() {
        String app = identity.applicationId() != null ? identity.applicationId() : identity.application();
        return app + "|" + datasource.name();
    }
}
