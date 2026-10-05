package org.dbplatform.gateway;

import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.common.telemetry.TelemetryClient;
import org.dbplatform.gateway.admin.AdminServer;
import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.config.StaticConfigLoader;
import org.dbplatform.gateway.control.ControlPlaneResolver;
import org.dbplatform.gateway.control.Resolver;
import org.dbplatform.gateway.control.StaticResolver;
import org.dbplatform.gateway.pool.PoolManager;
import org.dbplatform.gateway.server.GatewayServer;
import org.dbplatform.gateway.session.SessionHandler;
import org.dbplatform.gateway.session.SessionRegistry;
import org.dbplatform.gateway.telemetry.GatewayMetrics;
import org.dbplatform.gateway.telemetry.GatewayTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composition root of a gateway instance: resolver (static or control plane), pools, session registry, telemetry,
 * wire-protocol listener and admin endpoints.
 */
public final class Gateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

    private final GatewayConfig config;
    private final Resolver resolver;
    private final PoolManager pools;
    private final SessionRegistry sessions;
    private final GatewayMetrics metrics;
    private final GatewayTelemetry telemetry;
    private final GatewayServer server;
    private final AdminServer admin;
    private final ExecutorService networkTimeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean started;

    public Gateway(GatewayConfig config, Resolver resolver) {
        this.config = config;
        this.resolver = resolver;
        this.pools = new PoolManager(config.gatewayId());
        this.sessions = new SessionRegistry(config.gatewayId(), config.maxSessions());
        this.metrics = new GatewayMetrics();
        metrics.bind(pools, sessions::countFor, sessions::pinnedFor);
        TelemetryClient telemetryClient = null;
        ControlPlaneClient controlPlane = null;
        if (config.controlPlaneMode()) {
            String url = config.controlPlaneUrl().orElseThrow();
            telemetryClient = TelemetryClient.builder(url, config.serviceToken())
                    .flushInterval(Duration.ofMillis(org.dbplatform.common.util.Env.getLong("DBP_TELEMETRY_FLUSH_MS",
                            TelemetryClient.DEFAULT_FLUSH_INTERVAL_MS)))
                    .queueCapacity(org.dbplatform.common.util.Env.getInt("DBP_TELEMETRY_QUEUE_SIZE",
                            TelemetryClient.DEFAULT_QUEUE_CAPACITY))
                    .build();
            controlPlane = new ControlPlaneClient(url, config.serviceToken());
        }
        this.telemetry = new GatewayTelemetry(config.gatewayId(), metrics, telemetryClient, controlPlane, resolver, pools);
        telemetry.bindSessionCounts(sessions::size, sessions::countFor, sessions::pinnedFor);
        this.server = new GatewayServer(config, this::newHandler, sessions::executing);
        this.admin = new AdminServer(config, sessions, pools, telemetry, metrics, resolver);
    }

    /** Builds a gateway from the environment ({@code DBP_*}). */
    public static Gateway fromEnv() {
        GatewayConfig cfg = GatewayConfig.fromEnv();
        return new Gateway(cfg, resolverFor(cfg));
    }

    static Resolver resolverFor(GatewayConfig cfg) {
        if (cfg.controlPlaneMode()) {
            ControlPlaneClient client = new ControlPlaneClient(cfg.controlPlaneUrl().orElseThrow(), cfg.serviceToken());
            return new ControlPlaneResolver(client, Duration.ofSeconds(cfg.authCacheSeconds()),
                    Duration.ofSeconds(cfg.configPollSeconds()));
        }
        Path file = cfg.staticConfigFile().orElseThrow(() -> new IllegalStateException(
                "either DBP_CONTROL_PLANE_URL (control plane mode) or DBP_GATEWAY_CONFIG (static YAML) must be set"));
        StaticConfig sc = StaticConfigLoader.load(file);
        return new StaticResolver(sc);
    }

    private Runnable newHandler(java.net.Socket socket) {
        return new SessionHandler(socket, config, server.port(), resolver, pools, sessions, telemetry, metrics,
                networkTimeoutExecutor, server::isStopping);
    }

    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        resolver.start();
        telemetry.start(config.poolStatsSeconds(), config.heartbeatSeconds());
        server.start();
        admin.start();
        started = true;
        LOG.info("dbp-gateway {} started ({} mode)", Version.CURRENT, resolver.mode());
    }

    public int port() {
        return server.port();
    }

    public int adminPort() {
        return admin.port();
    }

    public GatewayConfig config() {
        return config;
    }

    public PoolManager pools() {
        return pools;
    }

    public SessionRegistry sessions() {
        return sessions;
    }

    public GatewayMetrics metrics() {
        return metrics;
    }

    public GatewayTelemetry telemetry() {
        return telemetry;
    }

    public Resolver resolver() {
        return resolver;
    }

    /** Graceful shutdown: stop accepting, wait for in-flight statements, close sessions, pools and telemetry. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        LOG.info("dbp-gateway {} stopping", config.gatewayId());
        server.stop(config.shutdownGraceSeconds());
        admin.close();
        for (var s : sessions.sessions()) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // best effort
            } finally {
                sessions.unregister(s);
            }
        }
        telemetry.flush();
        telemetry.close();
        pools.close();
        resolver.close();
        networkTimeoutExecutor.shutdownNow();
        LOG.info("dbp-gateway {} stopped", config.gatewayId());
    }

    @Override
    public void close() {
        stop();
    }
}
