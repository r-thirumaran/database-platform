package org.dbplatform.proxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.LiveConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer meters exposed on {@code GET /metrics} (Prometheus text format):
 * <ul>
 *   <li>{@code dbp_proxy_connections_active{listener,application,datasource,backend}}</li>
 *   <li>{@code dbp_proxy_connections_accepted_total{listener}}, {@code ..._refused_total{listener,reason}},
 *       {@code ..._failed_total{listener}}</li>
 *   <li>{@code dbp_proxy_bytes_in_total{listener}} / {@code dbp_proxy_bytes_out_total{listener}}</li>
 *   <li>{@code dbp_proxy_backend_connect_seconds{listener,backend}} (timer)</li>
 *   <li>{@code dbp_proxy_telemetry_events_dropped}</li>
 * </ul>
 */
public final class ProxyMetrics {
    private final PrometheusMeterRegistry registry;
    private final ConnectionRegistry connections;
    private final MultiGauge active;
    private final Map<String, Counter> accepted = new ConcurrentHashMap<>();
    private final Map<String, Counter> failed = new ConcurrentHashMap<>();
    private final Map<String, Counter> refused = new ConcurrentHashMap<>();
    private final Map<String, Counter> bytesIn = new ConcurrentHashMap<>();
    private final Map<String, Counter> bytesOut = new ConcurrentHashMap<>();
    private final Map<String, Timer> connectTimers = new ConcurrentHashMap<>();
    private volatile Supplier<Number> droppedEvents = () -> 0;

    public ProxyMetrics(ConnectionRegistry connections) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.connections = connections;
        this.active = MultiGauge.builder("dbp.proxy.connections.active")
                .description("Live proxied connections")
                .register(registry);
        Gauge.builder("dbp.proxy.connections.live", connections, ConnectionRegistry::size)
                .description("Live proxied connections (all listeners)")
                .register(registry);
        Gauge.builder("dbp.proxy.telemetry.events.dropped", this, m -> m.droppedEvents.get().doubleValue())
                .description("Telemetry events dropped because the control plane was unreachable")
                .register(registry);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public void setDroppedEventsSupplier(Supplier<Number> supplier) {
        this.droppedEvents = supplier;
    }

    public void accepted(String listener) {
        accepted.computeIfAbsent(listener, l -> Counter.builder("dbp.proxy.connections.accepted")
                .description("Client connections accepted").tag("listener", l).register(registry)).increment();
    }

    public void failed(String listener) {
        failed.computeIfAbsent(listener, l -> Counter.builder("dbp.proxy.connections.failed")
                .description("Connections whose backend could not be reached").tag("listener", l).register(registry)).increment();
    }

    public void refused(String listener, String reason) {
        refused.computeIfAbsent(listener + "|" + reason, k -> Counter.builder("dbp.proxy.connections.refused")
                .description("Connections refused by the proxy").tags("listener", listener, "reason", reason).register(registry)).increment();
    }

    public Counter bytesInCounter(String listener) {
        return bytesIn.computeIfAbsent(listener, l -> Counter.builder("dbp.proxy.bytes.in")
                .description("Bytes received from clients").baseUnit("bytes").tag("listener", l).register(registry));
    }

    public Counter bytesOutCounter(String listener) {
        return bytesOut.computeIfAbsent(listener, l -> Counter.builder("dbp.proxy.bytes.out")
                .description("Bytes sent to clients").baseUnit("bytes").tag("listener", l).register(registry));
    }

    public void recordConnect(String listener, String backend, long nanos) {
        connectTimers.computeIfAbsent(listener + "|" + backend, k -> Timer.builder("dbp.proxy.backend.connect")
                .description("Backend TCP connect latency").tags("listener", listener, "backend", backend)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Recompute the per-dimension active gauge from the registry and render the Prometheus exposition. */
    public String scrape() {
        refreshActive();
        return registry.scrape();
    }

    void refreshActive() {
        Map<Tags, Integer> counts = new HashMap<>();
        for (LiveConnection c : connections.all()) {
            Tags t = Tags.of("listener", c.listener(),
                    "application", c.application() == null ? "unknown" : c.application(),
                    "datasource", c.datasource() == null ? "none" : c.datasource(),
                    "backend", c.backend() == null ? "none" : c.backend());
            counts.merge(t, 1, Integer::sum);
        }
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (Map.Entry<Tags, Integer> e : counts.entrySet()) {
            rows.add(MultiGauge.Row.of(e.getKey(), e.getValue()));
        }
        active.register(rows, true);
    }
}
