package org.dbplatform.gateway.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.pool.PoolManager;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Micrometer metrics of the gateway, exposed in Prometheus format on the admin port.
 *
 * <pre>
 * dbp_gateway_logical_sessions{datasource}      dbp_gateway_pinned_sessions{datasource}
 * dbp_gateway_pool_active|idle|waiting|total|max{datasource}
 * dbp_gateway_statements_total{datasource,operation,success}
 * dbp_gateway_statement_duration_seconds{datasource} (histogram)
 * dbp_gateway_errors_total{sqlstate}            dbp_gateway_telemetry_dropped_total
 * </pre>
 */
public final class GatewayMetrics {

    private final PrometheusMeterRegistry registry;
    private final Map<String, Boolean> datasourceGauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private PoolManager pools;
    private ToIntFunction<String> logicalSessions = ds -> 0;
    private ToIntFunction<String> pinnedSessions = ds -> 0;

    public GatewayMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** Prometheus text exposition. */
    public String scrape() {
        return registry.scrape();
    }

    public void bind(PoolManager pools, ToIntFunction<String> logicalSessions, ToIntFunction<String> pinnedSessions) {
        this.pools = pools;
        this.logicalSessions = logicalSessions;
        this.pinnedSessions = pinnedSessions;
    }

    public void bindTelemetryDropped(java.util.function.LongSupplier dropped) {
        FunctionCounter.builder("dbp.gateway.telemetry.dropped", dropped, s -> (double) s.getAsLong())
                .description("telemetry events dropped because the queue was full or the control plane rejected them")
                .register(registry);
    }

    /** Ensures the per-datasource gauges exist (idempotent). */
    public void datasourceSeen(String datasource) {
        datasourceGauges.computeIfAbsent(datasource, ds -> {
            Tags tags = Tags.of("datasource", ds);
            Gauge.builder("dbp.gateway.logical.sessions", () -> logicalSessions.applyAsInt(ds)).tags(tags)
                    .description("open logical sessions").register(registry);
            Gauge.builder("dbp.gateway.pinned.sessions", () -> pinnedSessions.applyAsInt(ds)).tags(tags)
                    .description("logical sessions currently holding a physical connection").register(registry);
            poolGauge("dbp.gateway.pool.active", ds, PhysicalPool::activeConnections);
            poolGauge("dbp.gateway.pool.idle", ds, PhysicalPool::idleConnections);
            poolGauge("dbp.gateway.pool.waiting", ds, PhysicalPool::waitingThreads);
            poolGauge("dbp.gateway.pool.total", ds, PhysicalPool::totalConnections);
            poolGauge("dbp.gateway.pool.max", ds, PhysicalPool::maxConnections);
            return Boolean.TRUE;
        });
    }

    private void poolGauge(String name, String ds, ToDoubleFunction<PhysicalPool> f) {
        Gauge.builder(name, () -> {
            if (pools == null) {
                return 0d;
            }
            double sum = 0;
            for (PhysicalPool p : pools.poolsFor(ds)) {
                sum += f.applyAsDouble(p);
            }
            return sum;
        }).tags("datasource", ds).register(registry);
    }

    public void recordStatement(String datasource, String operation, boolean success, long nanos) {
        Counter.builder("dbp.gateway.statements")
                .tags("datasource", datasource, "operation", operation, "success", Boolean.toString(success))
                .register(registry).increment();
        timers.computeIfAbsent(datasource, ds -> Timer.builder("dbp.gateway.statement.duration")
                .tags("datasource", ds)
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10),
                        Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(10), Duration.ofSeconds(30))
                .register(registry)).record(Duration.ofNanos(nanos));
    }

    public void recordError(String sqlState) {
        Counter.builder("dbp.gateway.errors").tags("sqlstate", sqlState == null ? "unknown" : sqlState)
                .register(registry).increment();
    }
}
