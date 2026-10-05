package org.dbplatform.gateway.control;

import org.dbplatform.common.controlplane.CredentialMaterial;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.config.StaticConfigLoader;
import org.dbplatform.gateway.pool.PoolMode;
import org.dbplatform.gateway.pool.PoolSettings;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolver backed by a {@link StaticConfig} (no control plane).
 */
public final class StaticResolver implements Resolver {

    public static final int DEFAULT_MAX_CONNECTIONS = 10;
    public static final int DEFAULT_MIN_IDLE = 0;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 10_000;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000;
    public static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000;

    private final StaticConfig config;
    private final Map<String, ResolvedDatasource> resolved = new ConcurrentHashMap<>();

    public StaticResolver(StaticConfig config) {
        this.config = config;
    }

    public StaticConfig config() {
        return config;
    }

    @Override
    public String mode() {
        return "static";
    }

    @Override
    public SessionResolution resolve(String datasource, String apiKey, String applicationHint, String user)
            throws AuthException {
        StaticConfig.DatasourceConfig ds = config.datasource(datasource);
        if (ds == null) {
            throw AuthException.rejected("unknown datasource '" + datasource + "'");
        }
        Identity identity;
        StaticConfig.GrantConfig grant = null;
        if (config.hasApplications()) {
            if (apiKey == null || apiKey.isBlank()) {
                throw AuthException.rejected("apiKey is required for datasource '" + datasource + "'");
            }
            StaticConfig.ApplicationConfig app = null;
            byte[] presented = apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (StaticConfig.ApplicationConfig a : config.applications()) {
                if (a.apiKey() != null && java.security.MessageDigest.isEqual(presented,
                        a.apiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    app = a;
                    break;
                }
            }
            if (app == null) {
                throw AuthException.rejected("invalid api key");
            }
            grant = app.grant(datasource);
            if (grant == null) {
                throw AuthException.rejected("application '" + app.name() + "' is not authorised for datasource '"
                        + datasource + "'");
            }
            identity = new Identity(null, app.name(), null, app.team());
        } else {
            String hint = applicationHint != null && !applicationHint.isBlank() ? applicationHint : user;
            identity = Identity.anonymous(hint);
        }
        ResolvedDatasource rd = resolved.computeIfAbsent(datasource, n -> toResolved(ds));
        PoolMode mode = grant != null && grant.poolMode() != null
                ? PoolMode.parse(grant.poolMode(), rd.poolMode()) : rd.poolMode();
        boolean readOnly = grant != null && Boolean.TRUE.equals(grant.readOnly());
        int maxLogical = grant != null && grant.maxLogicalConnections() != null ? grant.maxLogicalConnections() : 0;
        return new SessionResolution(identity, rd, mode, readOnly, maxLogical, -1);
    }

    private static ResolvedDatasource toResolved(StaticConfig.DatasourceConfig ds) {
        Engine engine = ds.engine() != null ? Engine.parse(ds.engine()) : PoolSettings.engineFromUrl(ds.jdbcUrl());
        if (engine == Engine.OTHER) {
            engine = PoolSettings.engineFromUrl(ds.jdbcUrl());
        }
        PoolSettings settings = new PoolSettings(
                ds.name(), ds.name(), null, null, engine, ds.jdbcUrl(), ds.username(), null, ds.name(), 1,
                ds.maxConnections() != null ? ds.maxConnections() : DEFAULT_MAX_CONNECTIONS,
                ds.minIdle() != null ? ds.minIdle() : DEFAULT_MIN_IDLE,
                ds.connectionTimeoutMs() != null ? ds.connectionTimeoutMs() : DEFAULT_CONNECTION_TIMEOUT_MS,
                ds.idleTimeoutMs() != null ? ds.idleTimeoutMs() : DEFAULT_IDLE_TIMEOUT_MS,
                ds.maxLifetimeMs() != null ? ds.maxLifetimeMs() : DEFAULT_MAX_LIFETIME_MS,
                ds.statementTimeoutSeconds() != null ? ds.statementTimeoutSeconds() : 0,
                ds.validationQuery(), ds.jdbcProperties(), PoolMode.parse(ds.poolMode(), PoolMode.TRANSACTION));
        return new ResolvedDatasource(settings, null, null);
    }

    @Override
    public CredentialMaterial credentials(ResolvedDatasource datasource) {
        StaticConfig.DatasourceConfig ds = config.datasource(datasource.name());
        String password = ds == null ? "" : StaticConfigLoader.resolvePassword(ds);
        return new CredentialMaterial(datasource.settings().username(), password, 1);
    }

    @Override
    public boolean reachable() {
        return true;
    }

    @Override
    public Optional<Long> configVersion() {
        return Optional.empty();
    }
}
