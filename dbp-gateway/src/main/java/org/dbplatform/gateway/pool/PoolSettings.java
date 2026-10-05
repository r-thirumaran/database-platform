package org.dbplatform.gateway.pool;

import org.dbplatform.common.telemetry.Engine;

import java.util.Map;

/**
 * Everything needed to build one physical pool. Produced by a resolver (static config or control plane) plus the
 * credential material of the moment.
 *
 * @param databaseKey             identifies the physical database (control plane {@code databaseId}, else the datasource name)
 * @param datasourceName          logical datasource the pool was created for
 * @param datasourceId            control plane id (nullable)
 * @param databaseId              control plane id (nullable)
 * @param engineHint              engine as configured/resolved (confirmed from the product name on first connection)
 * @param jdbcUrl                 physical JDBC URL
 * @param username                physical user
 * @param password                physical secret
 * @param credentialId            credential reference (nullable in static mode)
 * @param credentialVersion       version of the credential material
 * @param maxConnections          hard cap of physical connections of this pool
 * @param minIdle                 Hikari minimum idle
 * @param connectionTimeoutMs     wait for a free connection before ERROR 08001
 * @param idleTimeoutMs           Hikari idle timeout
 * @param maxLifetimeMs           Hikari max lifetime
 * @param statementTimeoutSeconds cap applied to Statement.setQueryTimeout (0 = none)
 * @param validationQuery         Hikari connectionTestQuery (nullable = use isValid)
 * @param jdbcProperties          driver properties
 * @param poolMode                datasource-level pool mode (grant may override per session)
 */
public record PoolSettings(
        String databaseKey,
        String datasourceName,
        String datasourceId,
        String databaseId,
        Engine engineHint,
        String jdbcUrl,
        String username,
        String password,
        String credentialId,
        int credentialVersion,
        int maxConnections,
        int minIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        int statementTimeoutSeconds,
        String validationQuery,
        Map<String, String> jdbcProperties,
        PoolMode poolMode) {

    public PoolSettings {
        jdbcProperties = jdbcProperties == null ? Map.of() : Map.copyOf(jdbcProperties);
        poolMode = poolMode == null ? PoolMode.TRANSACTION : poolMode;
        engineHint = engineHint == null ? engineFromUrl(jdbcUrl) : engineHint;
    }

    /** Pool key: physical database plus credential version. */
    public String poolKey() {
        return databaseKey + "@v" + credentialVersion;
    }

    /** Derives the engine from a JDBC URL prefix. */
    public static Engine engineFromUrl(String url) {
        if (url == null) {
            return Engine.OTHER;
        }
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:oracle:")) {
            return Engine.ORACLE;
        }
        if (u.startsWith("jdbc:postgresql:")) {
            return Engine.POSTGRES;
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return Engine.MSSQL;
        }
        if (u.startsWith("jdbc:h2:")) {
            return Engine.H2;
        }
        return Engine.OTHER;
    }

    @Override
    public String toString() {
        return "PoolSettings[" + poolKey() + ", datasource=" + datasourceName + ", engine=" + engineHint + ", url="
                + jdbcUrl + ", user=" + username + ", max=" + maxConnections + ", minIdle=" + minIdle + ", mode=" + poolMode + "]";
    }
}
