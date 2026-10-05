package org.dbplatform.proxy.control;

import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.common.controlplane.ControlPlaneException;
import org.dbplatform.common.telemetry.ComponentType;
import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.ConnectionEventType;
import org.dbplatform.common.telemetry.Heartbeat;
import org.dbplatform.common.telemetry.TelemetryClient;
import org.dbplatform.common.util.Hostnames;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.telemetry.EventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Control-plane mode: fetches {@code GET /api/v1/internal/proxy/config?proxyId=…} at start, polls
 * {@code GET /api/v1/internal/config-version} every {@code DBP_CONFIG_POLL_SECONDS} and re-fetches on
 * change, and posts a {@link Heartbeat} with a live-connection snapshot every {@code DBP_HEARTBEAT_SECONDS}.
 * A heartbeat reply carrying a newer version also triggers a reload. Every poll also runs the maintenance
 * hook (listener re-bind). All loops run on virtual threads and survive control-plane outages (the last
 * good configuration stays in force).
 */
public final class ControlPlaneConfigSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneConfigSource.class);

    private final ProxySettings settings;
    private final ControlPlaneClient client;
    private final TelemetryClient telemetry;
    private final ConnectionRegistry registry;
    private final Consumer<ProxyConfigDocument> onConfig;
    private final Runnable onPoll;
    private final String version;
    private final Instant startedAt = Instant.now();
    private final ReentrantLock reloadLock = new ReentrantLock();
    private volatile long appliedVersion = -1;
    private volatile boolean running = true;
    private volatile Instant lastHeartbeatOk;
    private volatile String lastError;
    private Thread poller;
    private Thread heartbeater;

    public ControlPlaneConfigSource(ProxySettings settings, ControlPlaneClient client, TelemetryClient telemetry,
                                    ConnectionRegistry registry, String version, Consumer<ProxyConfigDocument> onConfig) {
        this(settings, client, telemetry, registry, version, onConfig, null);
    }

    /**
     * @param onPoll run once per poll after the version check, whether or not the control plane answered —
     *               used to re-bind listeners that failed to bind ({@code ProxyServer::repair}); may be null
     */
    public ControlPlaneConfigSource(ProxySettings settings, ControlPlaneClient client, TelemetryClient telemetry,
                                    ConnectionRegistry registry, String version, Consumer<ProxyConfigDocument> onConfig,
                                    Runnable onPoll) {
        this.settings = settings;
        this.client = client;
        this.telemetry = telemetry;
        this.registry = registry;
        this.version = version;
        this.onConfig = onConfig;
        this.onPoll = onPoll == null ? () -> { } : onPoll;
    }

    /** Fetch and apply the initial configuration, retrying until it succeeds (or {@code maxAttempts} is reached, 0 = forever). */
    public boolean fetchInitial(int maxAttempts) {
        int attempt = 0;
        while (running) {
            attempt++;
            if (reload("startup")) {
                return true;
            }
            if (maxAttempts > 0 && attempt >= maxAttempts) {
                return false;
            }
            sleepSeconds(Math.max(1, settings.configPollSeconds()));
        }
        return false;
    }

    public void start() {
        poller = Thread.ofVirtual().name("dbp-config-poller").start(this::pollLoop);
        heartbeater = Thread.ofVirtual().name("dbp-heartbeat").start(this::heartbeatLoop);
    }

    public long appliedVersion() {
        return appliedVersion;
    }

    /** Record that a configuration of this version is already in force (embedded start). */
    public void markApplied(long version) {
        this.appliedVersion = version;
    }

    public Instant lastHeartbeatOk() {
        return lastHeartbeatOk;
    }

    public String lastError() {
        return lastError;
    }

    /** Fetch the configuration now and apply it when its version differs from the one in force. */
    public boolean reload(String why) {
        reloadLock.lock();
        try {
            ProxyConfigDocument doc = client.get(ControlPlaneClient.INTERNAL + "/proxy/config?proxyId="
                    + URLEncoder.encode(settings.proxyId(), StandardCharsets.UTF_8), ProxyConfigDocument.class);
            if (doc == null) {
                lastError = "empty proxy config";
                LOG.warn("control plane returned an empty proxy configuration");
                return false;
            }
            if (doc.configVersion() == appliedVersion && appliedVersion >= 0) {
                return true;
            }
            LOG.info("applying configuration version {} from control plane ({})", doc.configVersion(), why);
            onConfig.accept(doc);
            appliedVersion = doc.configVersion();
            lastError = null;
            return true;
        } catch (ControlPlaneException e) {
            lastError = e.getMessage();
            LOG.warn("cannot fetch proxy configuration ({}): {}", why, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOG.error("invalid proxy configuration from control plane ({}): {}", why, e.toString(), e);
            return false;
        } finally {
            reloadLock.unlock();
        }
    }

    private void pollLoop() {
        while (running) {
            sleepSeconds(Math.max(1, settings.configPollSeconds()));
            if (!running) {
                return;
            }
            try {
                long v = client.configVersion();
                if (v != appliedVersion) {
                    reload("version " + appliedVersion + " -> " + v);
                }
            } catch (ControlPlaneException e) {
                lastError = e.getMessage();
                LOG.debug("config-version poll failed: {}", e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("config poll failed: {}", e.toString());
            }
            maintenance();
        }
    }

    /** Listener repair (failed bind at start-up, dead accept loop) is retried at most once per poll. */
    private void maintenance() {
        try {
            onPoll.run();
        } catch (RuntimeException e) {
            LOG.warn("periodic maintenance failed: {}", e.toString());
        }
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                client.heartbeat(buildHeartbeat()).ifPresent(v -> {
                    if (v != appliedVersion) {
                        reload("heartbeat reported version " + v);
                    }
                });
                lastHeartbeatOk = Instant.now();
            } catch (ControlPlaneException e) {
                lastError = e.getMessage();
                LOG.debug("heartbeat failed: {}", e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("heartbeat failed: {}", e.toString());
            }
            sleepSeconds(Math.max(1, settings.heartbeatSeconds()));
        }
    }

    Heartbeat buildHeartbeat() {
        List<ConnectionEvent> live = new ArrayList<>();
        for (LiveConnection c : registry.snapshot(Heartbeat.MAX_LIVE_CONNECTIONS)) {
            live.add(EventMapper.toEvent(c, ConnectionEventType.OPEN, settings.proxyId(), null));
        }
        long dropped = telemetry == null ? 0 : telemetry.droppedCount();
        return new Heartbeat(ComponentType.PROXY, settings.proxyId(), version, Hostnames.localHostName(), startedAt,
                appliedVersion < 0 ? null : appliedVersion, Heartbeat.Stats.proxy(registry.size(), dropped, live));
    }

    private void sleepSeconds(int s) {
        try {
            Thread.sleep(s * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        if (heartbeater != null) {
            heartbeater.interrupt();
        }
    }
}
