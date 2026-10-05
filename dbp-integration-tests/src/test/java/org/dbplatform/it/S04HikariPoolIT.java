package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.BackendSampler;
import org.dbplatform.it.support.ControlPlaneApi;
import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Sql;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 4: HikariCP in front of the driver (Spring Boot style) — many logical connections, few physical ones. */
@ExtendWith(ItExtension.class)
@Order(4)
@DisplayName("4 HikariCP + driver: logical vs physical connections")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S04HikariPoolIT {

    @Test
    @Order(1)
    void two_hundred_borrows_from_ten_threads_never_exceed_four_physical_connections_and_leak_nothing() throws Exception {
        Stack s = Stack.current();
        s.ensureGateway();
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.dbplatform.jdbc.DbpDriver");
        cfg.setJdbcUrl(s.dbpUrl(DS_SALES));
        cfg.setUsername(ORDERS);
        cfg.setPassword(s.apiKey(ORDERS));
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(10);
        cfg.setConnectionTimeout(15_000);
        cfg.setPoolName("it-orders");
        cfg.addDataSourceProperty("clientInfo.ApplicationName", ORDERS);
        // connectionTestQuery deliberately unset: Hikari validates with Connection.isValid (PING)
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger borrows = new AtomicInteger();
        int physicalMax;
        try (Connection superuser = s.pgSuperuser("postgres");
             BackendSampler sampler = new BackendSampler(superuser, "sales_app", "sales");
             HikariDataSource ds = new HikariDataSource(cfg)) {
            Await.until("Hikari pool filled with 10 logical connections", Duration.ofSeconds(30), () -> ds.getHikariPoolMXBean().getTotalConnections() >= 10);
            ExecutorService pool = Executors.newFixedThreadPool(10);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 10; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        try (Connection c = ds.getConnection()) {
                            int n = borrows.incrementAndGet();
                            long cnt = Sql.queryLong(c, "SELECT count(*) FROM customer WHERE id = ?", (long) (1 + (thread * 20 + i) % 200));
                            assertThat(cnt).isEqualTo(1);
                            if (i % 5 == 0) {
                                c.setAutoCommit(false);
                                Sql.update(c, "UPDATE inventory SET updated_at = CURRENT_TIMESTAMP WHERE product_id = ?", (long) (1 + n % 60));
                                c.commit();
                                c.setAutoCommit(true);
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    }
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).as("workers finished").isTrue();
            for (Future<?> f : futures) {
                f.get();
            }
            assertThat(errors).as("errors during borrow/return: " + errors).isEmpty();
            assertThat(borrows.get()).isEqualTo(200);
            Await.until("all Hikari connections returned", Duration.ofSeconds(10), () -> ds.getHikariPoolMXBean().getActiveConnections() == 0);
            assertThat(ds.getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(10);
            assertThat(sampler.failure()).isNull();
            physicalMax = sampler.max();
            assertThat(sampler.samples()).isGreaterThan(10);
            assertThat(physicalMax).as("sales_app backends on PostgreSQL never exceed poolPolicy.maxConnections").isBetween(1, Stack.SALES_MAX_CONNECTIONS);

            JsonNode health = s.gatewayAdmin("/health");
            assertThat(health.path("logicalSessions").asInt()).as("one logical session per Hikari connection").isEqualTo(ds.getHikariPoolMXBean().getTotalConnections());
            JsonNode salesPool = ControlPlaneApi.stream(health.path("pools")).filter(p -> ControlPlaneApi.stream(p.path("datasources")).anyMatch(d -> DS_SALES.equals(d.asText())))
                    .findFirst().orElseThrow();
            assertThat(salesPool.path("max").asInt()).isEqualTo(Stack.SALES_MAX_CONNECTIONS);
            assertThat(salesPool.path("total").asInt()).isLessThanOrEqualTo(Stack.SALES_MAX_CONNECTIONS);
            assertThat(salesPool.path("username").asText()).isEqualTo("sales_app");
            Results.note("200 borrows / 10 threads / 10 Hikari connections: physical max observed in pg_stat_activity = %d (cap %d), gateway pool total=%d idle=%d, logicalSessions=%d",
                    physicalMax, Stack.SALES_MAX_CONNECTIONS, salesPool.path("total").asInt(), salesPool.path("idle").asInt(), health.path("logicalSessions").asInt());
        }
        Await.until("logical sessions released after the Hikari pool closed", Duration.ofSeconds(15), () -> s.gatewayAdmin("/health").path("logicalSessions").asInt() == 0);
        JsonNode poolStats = Await.until("GET /stats/pools has it-gw/sales", Duration.ofSeconds(20), () ->
                ControlPlaneApi.items(s.cp().get("/stats/pools").json()).filter(p -> Stack.GATEWAY_ID.equals(p.path("gatewayId").asText()) && DS_SALES.equals(p.path("datasourceName").asText())).findFirst());
        assertThat(poolStats.path("max").asInt()).isEqualTo(Stack.SALES_MAX_CONNECTIONS);
        Results.note("control plane /stats/pools: max=%d total=%d credentialVersion=%d", poolStats.path("max").asInt(), poolStats.path("total").asInt(), poolStats.path("credentialVersion").asInt());
    }

    @Test
    @Order(2)
    void pool_exhaustion_is_reported_as_08001_and_not_fatal() throws Exception {
        Stack s = Stack.current();
        // pin all 4 physical connections with open transactions, then a 5th logical connection must wait and time out (connectionTimeoutMs=10000)
        List<Connection> pinned = new ArrayList<>();
        try {
            for (int i = 0; i < Stack.SALES_MAX_CONNECTIONS; i++) {
                Connection c = s.connect(DS_SALES, ORDERS);
                c.setAutoCommit(false);
                Sql.queryLong(c, "SELECT count(*) FROM product");
                pinned.add(c);
            }
            try (Connection fifth = s.connect(DS_SALES, ORDERS)) {
                long t0 = System.nanoTime();
                java.sql.SQLException ex = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () -> Sql.queryLong(fifth, "SELECT 1"));
                long waited = (System.nanoTime() - t0) / 1_000_000;
                assertThat(ex.getSQLState()).isEqualTo("08001");
                assertThat(waited).isBetween(5_000L, 60_000L);
                pinned.get(0).commit();
                pinned.get(0).close();
                pinned.remove(0);
                assertThat(Sql.queryLong(fifth, "SELECT 1")).as("the session survives 08001 and succeeds once a physical connection is free").isEqualTo(1);
                Results.note("4 pinned transactions saturate the pool; 5th logical session got 08001 after %d ms and recovered after a release", waited);
            }
        } finally {
            for (Connection c : pinned) {
                try {
                    c.rollback();
                    c.close();
                } catch (Exception ignored) {
                    // cleanup
                }
            }
        }
        Optional<JsonNode> ignored = Optional.empty();
        assertThat(ignored).isEmpty();
    }
}
