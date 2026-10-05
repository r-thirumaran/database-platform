package org.dbplatform.proxy;

import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.common.telemetry.TelemetryClient;
import org.dbplatform.common.util.Env;
import org.dbplatform.proxy.admin.AdminServer;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.control.ControlPlaneConfigSource;
import org.dbplatform.proxy.control.StaticConfigSource;
import org.dbplatform.proxy.metrics.ProxyMetrics;
import org.dbplatform.proxy.net.ProxyRuntime;
import org.dbplatform.proxy.net.ProxyServer;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.QuotaManager;
import org.dbplatform.proxy.telemetry.ConnectionEvents;
import org.dbplatform.proxy.telemetry.TelemetryConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires registry, quotas, metrics, telemetry, listeners, admin API and the configuration source
 * (control plane or static YAML) into one process. Also usable embedded (tests, examples).
 */
public final class ProxyApp implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyApp.class);
    public static final String VERSION = version();

    private final ProxySettings settings;
    private final ConnectionRegistry registry;
    private final ProxyMetrics metrics;
    private final ProxyServer server;
    private final AdminServer admin;
    private final TelemetryClient telemetry;
    private final ControlPlaneClient controlPlane;
    private final ControlPlaneConfigSource controlPlaneSource;
    private final StaticConfigSource staticSource;

    private ProxyApp(ProxySettings settings, ConnectionEvents eventsOverride, ProxyConfigDocument initial, boolean startAdmin) throws IOException {
        this.settings = settings;
        this.registry = new ConnectionRegistry();
        this.metrics = new ProxyMetrics(registry);
        QuotaManager quotas = new QuotaManager(registry, settings.unknownAppMaxConnections());

        ConnectionEvents events = eventsOverride;
        if (settings.controlPlaneMode()) {
            this.controlPlane = new ControlPlaneClient(settings.controlPlaneUrl(), settings.serviceToken());
            this.telemetry = TelemetryClient.builder(settings.controlPlaneUrl(), settings.serviceToken())
                    .flushInterval(Env.getDuration("DBP_TELEMETRY_FLUSH_MS", Duration.ofMillis(TelemetryClient.DEFAULT_FLUSH_INTERVAL_MS)))
                    .queueCapacity(Env.getInt("DBP_TELEMETRY_QUEUE_SIZE", TelemetryClient.DEFAULT_QUEUE_CAPACITY))
                    .build();
            metrics.setDroppedEventsSupplier(telemetry::droppedCount);
            if (events == null) {
                events = new TelemetryConnectionEvents(telemetry, settings.proxyId());
            }
        } else {
            this.controlPlane = null;
            this.telemetry = null;
        }
        if (events == null) {
            events = ConnectionEvents.NOOP;
        }
        ProxyRuntime rt = new ProxyRuntime(settings, registry, quotas, metrics, events);
        this.server = new ProxyServer(rt);

        if (settings.controlPlaneMode()) {
            this.controlPlaneSource = new ControlPlaneConfigSource(settings, controlPlane, telemetry, registry, VERSION, server::apply, server::repair);
            this.staticSource = null;
        } else if (settings.staticConfigPath() != null) {
            this.controlPlaneSource = null;
            this.staticSource = new StaticConfigSource(Path.of(settings.staticConfigPath()), settings.configPollSeconds(), server::apply, server::repair);
        } else {
            this.controlPlaneSource = null;
            this.staticSource = null;
        }
        if (initial != null) {
            server.apply(initial);
        }
        this.admin = startAdmin ? new AdminServer(settings, server, registry, metrics, this::status) : null;
    }

    /** Production entry: configuration from the control plane or {@code DBP_PROXY_CONFIG}. */
    public static ProxyApp start(ProxySettings settings) throws IOException {
        if (!settings.controlPlaneMode() && settings.staticConfigPath() == null) {
            throw new IllegalStateException("Set DBP_CONTROL_PLANE_URL (+ DBP_SERVICE_TOKEN, DBP_PROXY_ID) or DBP_PROXY_CONFIG=/path/proxy.yaml");
        }
        ProxyApp app = new ProxyApp(settings, null, null, true);
        app.admin.start();
        if (app.controlPlaneSource != null) {
            LOG.info("proxy {} v{} starting in control-plane mode ({})", settings.proxyId(), VERSION, settings.controlPlaneUrl());
            app.controlPlaneSource.fetchInitial(0);
            app.controlPlaneSource.start();
        } else {
            LOG.info("proxy {} v{} starting in static mode ({})", settings.proxyId(), VERSION, settings.staticConfigPath());
            app.staticSource.loadInitial();
            app.staticSource.start();
        }
        return app;
    }

    /**
     * Embedded start with an explicit configuration (tests / examples). In control-plane mode telemetry
     * and heartbeats are active and the control plane is polled; the given configuration is applied first.
     */
    public static ProxyApp startEmbedded(ProxySettings settings, ProxyConfigDocument config, ConnectionEvents events, boolean startAdmin) throws IOException {
        ProxyApp app = new ProxyApp(settings, events, config, startAdmin);
        if (app.admin != null) {
            app.admin.start();
        }
        if (app.controlPlaneSource != null) {
            if (config != null) {
                app.controlPlaneSource.markApplied(config.configVersion());
            }
            app.controlPlaneSource.start();
        }
        return app;
    }

    public ProxySettings settings() {
        return settings;
    }

    public ConnectionRegistry registry() {
        return registry;
    }

    public ProxyMetrics metrics() {
        return metrics;
    }

    public ProxyServer server() {
        return server;
    }

    public AdminServer admin() {
        return admin;
    }

    public TelemetryClient telemetry() {
        return telemetry;
    }

    public ControlPlaneConfigSource controlPlaneSource() {
        return controlPlaneSource;
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        if (telemetry != null) {
            m.put("telemetry", Map.of("sent", telemetry.sentCount(), "dropped", telemetry.droppedCount(),
                    "queued", telemetry.queuedCount(), "failedBatches", telemetry.failedBatchCount()));
        }
        if (controlPlaneSource != null) {
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("url", settings.controlPlaneUrl());
            cp.put("appliedVersion", controlPlaneSource.appliedVersion());
            cp.put("lastHeartbeatOk", controlPlaneSource.lastHeartbeatOk() == null ? null : controlPlaneSource.lastHeartbeatOk().toString());
            cp.put("lastError", controlPlaneSource.lastError());
            m.put("controlPlane", cp);
        }
        return m;
    }

    public void awaitTermination() throws InterruptedException {
        Thread.currentThread().join();
    }

    @Override
    public void close() {
        LOG.info("shutting down");
        if (controlPlaneSource != null) {
            controlPlaneSource.close();
        }
        if (staticSource != null) {
            staticSource.close();
        }
        if (admin != null) {
            admin.close();
        }
        server.close();
        if (telemetry != null) {
            telemetry.close();
        }
    }

    private static String version() {
        String v = ProxyApp.class.getPackage() == null ? null : ProxyApp.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "0.1.0-SNAPSHOT" : v;
    }
}
