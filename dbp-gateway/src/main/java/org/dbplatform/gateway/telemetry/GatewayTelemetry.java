package org.dbplatform.gateway.telemetry;

import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.common.sql.SqlAnalysis;
import org.dbplatform.common.sql.SqlAnalyzer;
import org.dbplatform.common.telemetry.ComponentType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.Heartbeat;
import org.dbplatform.common.telemetry.PoolStats;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TelemetryClient;
import org.dbplatform.common.util.Hostnames;
import org.dbplatform.common.util.Ids;
import org.dbplatform.gateway.Version;
import org.dbplatform.gateway.control.Resolver;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.pool.PoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * Builds {@link QueryEvent}s (never blocking the data path), reports {@link PoolStats} and heartbeats to the control
 * plane and keeps the Micrometer metrics up to date. Without a control plane only the metrics are maintained.
 */
public final class GatewayTelemetry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayTelemetry.class);

    /** What a statement execution looked like, as seen by the session. */
    public record StatementOutcome(String sql, long durationNanos, long rows, SQLException error, boolean pinned) {
    }

    /** Per-session facts needed on every event. */
    public record SessionFacts(String sessionId, String applicationId, String application, String team, String datasource,
                               String databaseId, Engine engine, String poolMode, Map<String, String> clientInfo,
                               String defaultSchema) {
    }

    private final String gatewayId;
    private final GatewayMetrics metrics;
    private final SqlAnalyzer analyzer = new SqlAnalyzer();
    private final TelemetryClient client;          // null without control plane
    private final ControlPlaneClient controlPlane; // null without control plane
    private final Resolver resolver;
    private final PoolManager pools;
    private final Instant startedAt = Instant.now();
    private ScheduledExecutorService scheduler;
    private IntSupplier logicalSessions = () -> 0;
    private ToIntFunction<String> logicalSessionsFor = ds -> 0;
    private ToIntFunction<String> pinnedSessionsFor = ds -> 0;
    private volatile Instant lastHeartbeatOk;

    public GatewayTelemetry(String gatewayId, GatewayMetrics metrics, TelemetryClient client,
                            ControlPlaneClient controlPlane, Resolver resolver, PoolManager pools) {
        this.gatewayId = gatewayId;
        this.metrics = metrics;
        this.client = client;
        this.controlPlane = controlPlane;
        this.resolver = resolver;
        this.pools = pools;
        if (client != null) {
            metrics.bindTelemetryDropped(client::droppedCount);
        } else {
            metrics.bindTelemetryDropped(() -> 0L);
        }
    }

    public void bindSessionCounts(IntSupplier total, ToIntFunction<String> perDatasource, ToIntFunction<String> pinnedPerDatasource) {
        this.logicalSessions = total;
        this.logicalSessionsFor = perDatasource;
        this.pinnedSessionsFor = pinnedPerDatasource;
    }

    public boolean enabled() {
        return client != null;
    }

    public long eventsDropped() {
        return client == null ? 0 : client.droppedCount();
    }

    public Optional<Instant> lastHeartbeatOk() {
        return Optional.ofNullable(lastHeartbeatOk);
    }

    public SqlAnalyzer analyzer() {
        return analyzer;
    }

    // ------------------------------------------------------------------ statements

    /** Records one EXECUTE / EXECUTE_BATCH outcome: metrics always, QueryEvent when a control plane is configured. */
    public void recordStatement(SessionFacts s, StatementOutcome o) {
        SqlAnalysis a;
        try {
            a = analyzer.analyze(o.sql(), s.engine());
        } catch (RuntimeException e) {
            a = new SqlAnalysis(SqlOperation.OTHER, null, null, null, o.sql(), SqlAnalyzer.hash(o.sql()), false);
        }
        boolean success = o.error() == null;
        metrics.recordStatement(s.datasource(), a.operation().name(), success, o.durationNanos());
        if (!success) {
            metrics.recordError(o.error().getSQLState());
        }
        if (client == null) {
            return;
        }
        QueryEvent.Builder b = QueryEvent.builder()
                .eventId(Ids.ulid())
                .timestamp(Instant.now())
                .gatewayId(gatewayId)
                .sessionId(s.sessionId())
                .applicationId(s.applicationId())
                .application(s.application())
                .team(s.team())
                .datasource(s.datasource())
                .databaseId(s.databaseId())
                .engine(s.engine())
                .sqlHash(a.sqlHash())
                .sqlNormalized(a.normalizedSql())
                .operation(a.operation())
                .tables(a.tables())
                .routines(a.routines())
                .columns(a.columns())
                .defaultSchema(s.defaultSchema())
                .durationMs(Math.max(0, o.durationNanos() / 1_000_000))
                .rows(o.rows())
                .success(success)
                .pinned(o.pinned())
                .poolMode(s.poolMode())
                .clientInfo(s.clientInfo() == null || s.clientInfo().isEmpty() ? null : Map.copyOf(s.clientInfo()));
        if (!success) {
            b.sqlState(o.error().getSQLState()).errorCode(o.error().getErrorCode()).errorMessage(o.error().getMessage());
        }
        try {
            client.record(b.build());
        } catch (RuntimeException e) {
            LOG.debug("QueryEvent dropped: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ periodic reporting

    public synchronized void start(int poolStatsSeconds, int heartbeatSeconds) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dbp-telemetry-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeSweep, 1, 1, TimeUnit.SECONDS);
        if (client != null) {
            long ps = Math.max(1, poolStatsSeconds);
            scheduler.scheduleWithFixedDelay(safe(this::reportPoolStats), ps, ps, TimeUnit.SECONDS);
        }
        if (controlPlane != null) {
            long hb = Math.max(1, heartbeatSeconds);
            scheduler.scheduleWithFixedDelay(safe(this::heartbeat), 0, hb, TimeUnit.SECONDS);
        }
    }

    private Runnable safe(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.debug("telemetry task failed: {}", e.toString());
            }
        };
    }

    private void safeSweep() {
        try {
            pools.sweep();
        } catch (RuntimeException e) {
            LOG.debug("pool sweep failed: {}", e.toString());
        }
    }

    /** Builds the PoolStats of every live pool (also used by the admin endpoint). */
    public java.util.List<PoolStats> poolStats() {
        java.util.List<PoolStats> out = new java.util.ArrayList<>();
        Instant now = Instant.now();
        for (PhysicalPool p : pools.pools()) {
            for (String ds : p.datasourceNames()) {
                out.add(new PoolStats(now, gatewayId, ds, p.settings().datasourceId(), p.settings().databaseId(),
                        p.engine(), p.activeConnections(), p.idleConnections(), p.waitingThreads(), p.totalConnections(),
                        p.maxConnections(), logicalSessionsFor.applyAsInt(ds), pinnedSessionsFor.applyAsInt(ds),
                        p.settings().credentialVersion()));
            }
        }
        return out;
    }

    void reportPoolStats() {
        if (client == null) {
            return;
        }
        for (PoolStats s : poolStats()) {
            client.record(s);
        }
    }

    void heartbeat() {
        if (controlPlane == null) {
            return;
        }
        Heartbeat hb = new Heartbeat(ComponentType.GATEWAY, gatewayId, Version.CURRENT, Hostnames.localHostName(),
                startedAt, resolver.configVersion().orElse(null),
                Heartbeat.Stats.gateway(logicalSessions.getAsInt(), pools.physicalConnections(), eventsDropped()));
        try {
            Optional<Long> v = controlPlane.heartbeat(hb);
            lastHeartbeatOk = Instant.now();
            v.ifPresent(resolver::onConfigVersion);
        } catch (RuntimeException e) {
            LOG.debug("heartbeat failed: {}", e.toString());
        }
    }

    public void flush() {
        if (client != null) {
            client.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (client != null) {
            client.close();
        }
    }
}
