package org.dbplatform.gateway.control;

import org.dbplatform.common.controlplane.ApplicationIdentity;
import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.common.controlplane.ControlPlaneException;
import org.dbplatform.common.controlplane.CredentialMaterial;
import org.dbplatform.common.controlplane.DatasourceResolution;
import org.dbplatform.common.controlplane.PoolPolicy;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.gateway.pool.PoolMode;
import org.dbplatform.gateway.pool.PoolSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Resolver backed by the control plane ({@code /api/v1/internal/*}). Positive authentication results are cached for
 * {@code authCacheSeconds}, negative ones for 5 s; datasource resolutions are cached per (datasource, application)
 * until the control plane's {@code configVersion} changes (polled every {@code configPollSeconds}). When the control
 * plane is unreachable, stale cache entries keep serving the data path.
 */
public final class ControlPlaneResolver implements Resolver {

    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneResolver.class);

    public static final Duration NEGATIVE_AUTH_TTL = Duration.ofSeconds(5);
    /** Safety net so a resolution never outlives this even if version polling fails silently. */
    public static final Duration RESOLUTION_MAX_AGE = Duration.ofMinutes(10);

    private record AuthEntry(ApplicationIdentity identity, AuthException failure, long expiresAt) {
        boolean expired() {
            return System.nanoTime() > expiresAt;
        }
    }

    private record ResolveEntry(SessionResolution resolution, long fetchedAt) {
        boolean stale() {
            return System.nanoTime() - fetchedAt > RESOLUTION_MAX_AGE.toNanos();
        }
    }

    private final ControlPlaneClient client;
    private final Duration authTtl;
    private final Duration pollInterval;
    private final Map<String, AuthEntry> authCache = new ConcurrentHashMap<>();
    private final Map<String, ResolveEntry> resolveCache = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile long configVersion = -1;
    private volatile boolean reachable;
    private ScheduledExecutorService poller;

    public ControlPlaneResolver(ControlPlaneClient client, Duration authTtl, Duration pollInterval) {
        this.client = client;
        this.authTtl = authTtl;
        this.pollInterval = pollInterval;
    }

    @Override
    public String mode() {
        return "control-plane";
    }

    @Override
    public synchronized void start() {
        if (poller != null) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dbp-config-poller");
            t.setDaemon(true);
            return t;
        });
        long millis = Math.max(500, pollInterval.toMillis());
        poller.scheduleWithFixedDelay(this::poll, 0, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    void poll() {
        try {
            long v = client.configVersion();
            reachable = true;
            onConfigVersion(v);
        } catch (RuntimeException e) {
            reachable = false;
            LOG.debug("config-version poll failed: {}", e.toString());
        }
    }

    @Override
    public void onConfigVersion(long version) {
        long previous = configVersion;
        if (previous != version) {
            configVersion = version;
            if (previous != -1) {
                LOG.info("control plane config version {} -> {}: invalidating resolution caches", previous, version);
                resolveCache.clear();
                authCache.clear();
                for (Runnable l : listeners) {
                    try {
                        l.run();
                    } catch (RuntimeException e) {
                        LOG.warn("config listener failed: {}", e.toString());
                    }
                }
            }
        }
    }

    @Override
    public void addConfigListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public boolean reachable() {
        return reachable;
    }

    @Override
    public Optional<Long> configVersion() {
        return configVersion < 0 ? Optional.empty() : Optional.of(configVersion);
    }

    // ------------------------------------------------------------------ resolution

    @Override
    public SessionResolution resolve(String datasource, String apiKey, String applicationHint, String user)
            throws AuthException {
        if (apiKey == null || apiKey.isBlank()) {
            throw AuthException.rejected("apiKey is required");
        }
        ApplicationIdentity app = authenticate(apiKey);
        String key = datasource + "|" + app.applicationId();
        ResolveEntry cached = resolveCache.get(key);
        if (cached != null && !cached.stale()) {
            return cached.resolution();
        }
        try {
            DatasourceResolution r = client.resolveDatasource(datasource, app.applicationId());
            if (r == null || r.database() == null) {
                throw AuthException.rejected("control plane returned no database for datasource '" + datasource + "'");
            }
            reachable = true;
            SessionResolution res = toResolution(app, datasource, r);
            resolveCache.put(key, new ResolveEntry(res, System.nanoTime()));
            onConfigVersion(r.configVersion());
            return res;
        } catch (ControlPlaneException e) {
            if (e.isForbidden()) {
                throw AuthException.rejected("application '" + app.name() + "' is not authorised for datasource '"
                        + datasource + "'");
            }
            if (e.isNotFound()) {
                throw AuthException.rejected("unknown datasource '" + datasource + "'");
            }
            if (e.status() == 401) {
                throw AuthException.rejected("service token rejected by the control plane");
            }
            if (cached != null) {
                LOG.warn("control plane unavailable ({}), using stale resolution of {}", e.getMessage(), key);
                return cached.resolution();
            }
            reachable = false;
            throw AuthException.unreachable("control plane unavailable while resolving datasource '" + datasource
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public SessionResolution refresh(SessionResolution previous) {
        String key = previous.datasource().name() + "|" + previous.identity().applicationId();
        ResolveEntry cached = resolveCache.get(key);
        if (cached != null && !cached.stale()) {
            return cached.resolution();
        }
        try {
            DatasourceResolution r = client.resolveDatasource(previous.datasource().name(), previous.identity().applicationId());
            if (r == null || r.database() == null) {
                return previous;
            }
            reachable = true;
            ApplicationIdentity app = new ApplicationIdentity(previous.identity().applicationId(),
                    previous.identity().application(), previous.identity().teamId(), previous.identity().team(), List.of());
            SessionResolution res = toResolution(app, previous.datasource().name(), r);
            resolveCache.put(key, new ResolveEntry(res, System.nanoTime()));
            onConfigVersion(r.configVersion());
            return res;
        } catch (ControlPlaneException e) {
            LOG.debug("refresh of {} failed ({}), keeping previous resolution", key, e.getMessage());
            return previous;
        }
    }

    private ApplicationIdentity authenticate(String apiKey) throws AuthException {
        AuthEntry entry = authCache.get(apiKey);
        if (entry != null && !entry.expired()) {
            if (entry.failure() != null) {
                throw entry.failure();
            }
            return entry.identity();
        }
        try {
            ApplicationIdentity id = client.authenticateApplication(apiKey);
            if (id == null || id.applicationId() == null) {
                throw AuthException.rejected("invalid api key");
            }
            reachable = true;
            authCache.put(apiKey, new AuthEntry(id, null, System.nanoTime() + authTtl.toNanos()));
            return id;
        } catch (ControlPlaneException e) {
            if (e.isUnauthorized() || e.isForbidden() || e.isNotFound() || e.status() == 400) {
                AuthException failure = AuthException.rejected("invalid api key");
                authCache.put(apiKey, new AuthEntry(null, failure, System.nanoTime() + NEGATIVE_AUTH_TTL.toNanos()));
                throw failure;
            }
            if (entry != null && entry.identity() != null) {
                LOG.warn("control plane unavailable ({}), using cached identity of {}", e.getMessage(), entry.identity().name());
                return entry.identity();
            }
            reachable = false;
            throw AuthException.unreachable("control plane unavailable while authenticating: " + e.getMessage(), e);
        }
    }

    static SessionResolution toResolution(ApplicationIdentity app, String datasourceName, DatasourceResolution r) {
        DatasourceResolution.DatabaseInfo db = r.database();
        DatasourceResolution.CredentialRef cred = r.credential();
        PoolPolicy policy = r.poolPolicy() != null ? r.poolPolicy() : PoolPolicy.defaults();
        Engine engine = db.engine() != null ? db.engine() : PoolSettings.engineFromUrl(db.jdbcUrl());
        String jdbcUrl = db.jdbcUrl() != null ? db.jdbcUrl() : buildJdbcUrl(engine, db);
        PoolMode dsMode = PoolMode.TRANSACTION;
        PoolMode mode = r.grant() != null ? PoolMode.parse(r.grant().poolMode(), dsMode) : dsMode;
        int statementTimeoutSeconds = policy.statementTimeoutMs() == null || policy.statementTimeoutMs() <= 0
                ? 0 : (int) Math.max(1, (policy.statementTimeoutMs() + 999) / 1000);
        String dsName = r.datasource() != null && r.datasource().name() != null ? r.datasource().name() : datasourceName;
        PoolSettings settings = new PoolSettings(
                db.id() != null ? db.id() : dsName, dsName,
                r.datasource() != null ? r.datasource().id() : null, db.id(), engine, jdbcUrl,
                cred != null ? cred.username() : null, null,
                cred != null ? cred.id() : null, cred != null ? cred.version() : 1,
                policy.maxSizeOr(20), policy.minIdleOr(1), policy.connectionTimeoutMsOr(10_000),
                policy.idleTimeoutMsOr(600_000), policy.maxLifetimeMsOr(1_800_000), statementTimeoutSeconds,
                policy.validationQuery(), db.jdbcProperties(), mode);
        ResolvedDatasource rd = new ResolvedDatasource(settings, r.datasource() != null ? r.datasource().id() : null, db.id());
        Identity identity = new Identity(app.applicationId(), app.name(), app.teamId(), app.teamName());
        boolean readOnly = r.grant() != null && r.grant().readOnly();
        int maxLogical = r.grant() != null ? Math.max(0, r.grant().maxLogicalConnections()) : 0;
        return new SessionResolution(identity, rd, mode, readOnly, maxLogical, r.configVersion());
    }

    static String buildJdbcUrl(Engine engine, DatasourceResolution.DatabaseInfo db) {
        return switch (engine) {
            case ORACLE -> "jdbc:oracle:thin:@//" + db.host() + ":" + db.port() + "/" + db.serviceName();
            case POSTGRES -> "jdbc:postgresql://" + db.host() + ":" + db.port() + "/" + db.serviceName();
            case MSSQL -> "jdbc:sqlserver://" + db.host() + ":" + db.port() + ";databaseName=" + db.serviceName();
            default -> db.serviceName();
        };
    }

    @Override
    public CredentialMaterial credentials(ResolvedDatasource datasource) throws AuthException {
        try {
            CredentialMaterial m = client.credentialMaterial(datasource.credentialId());
            if (m == null) {
                throw AuthException.unreachable("control plane returned no credential material for "
                        + datasource.credentialId(), null);
            }
            return m;
        } catch (ControlPlaneException e) {
            throw AuthException.unreachable("cannot fetch credential material for datasource '" + datasource.name()
                    + "': " + e.getMessage(), e);
        }
    }

    /** Visible for tests. */
    int cachedResolutions() {
        return resolveCache.size();
    }
}
