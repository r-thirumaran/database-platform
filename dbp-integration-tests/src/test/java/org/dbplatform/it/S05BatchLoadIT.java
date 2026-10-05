package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.BackendSampler;
import org.dbplatform.it.support.ControlPlaneApi;
import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.Stack.BATCH;
import static org.dbplatform.it.support.Stack.DS_SALES;

/** Scenario 5: the reporting-batch workload (dbp-examples/reporting-batch Workload) on virtual threads. */
@ExtendWith(ItExtension.class)
@Order(5)
@DisplayName("5 Reporting-batch load: 20 logical x 50 statements")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S05BatchLoadIT {

    static final int CONNECTIONS = 20;
    static final int STATEMENTS = 50;

    /** The seven SELECTs of dbp-examples/reporting-batch/Workload.java (portable SQL, unqualified names). */
    static final List<String> SELECTS = List.of(
            "SELECT STATUS, COUNT(*) AS CNT, SUM(TOTAL_AMOUNT) AS TOTAL FROM ORDERS GROUP BY STATUS",
            """
            SELECT p.CATEGORY, SUM(oi.LINE_TOTAL) AS REVENUE, COUNT(DISTINCT o.ID) AS ORDERS_CNT
              FROM ORDER_ITEM oi
              JOIN PRODUCT p ON p.ID = oi.PRODUCT_ID
              JOIN ORDERS  o ON o.ID = oi.ORDER_ID
             WHERE o.STATUS <> 'CANCELLED'
             GROUP BY p.CATEGORY
             ORDER BY REVENUE DESC""",
            "SELECT c.ID, c.EMAIL, c.COUNTRY_CODE, c.STATUS FROM CUSTOMER c WHERE c.ID = ?",
            """
            SELECT o.ID, o.ORDER_NO, o.STATUS, o.TOTAL_AMOUNT
              FROM ORDERS o
             WHERE o.CUSTOMER_ID = ?
             ORDER BY o.ORDER_DATE DESC
             FETCH FIRST 10 ROWS ONLY""",
            "SELECT COUNT(*) AS CNT, COALESCE(SUM(AMOUNT), 0) AS TOTAL FROM PAYMENT WHERE STATUS = 'CAPTURED' AND PAID_AT >= ?",
            "SELECT CUSTOMER_ID, ORDER_COUNT, LIFETIME_VALUE FROM V_CUSTOMER_ORDER_SUMMARY WHERE CUSTOMER_ID = ?",
            "SELECT i.PRODUCT_ID, i.QTY_ON_HAND, i.QTY_RESERVED FROM INVENTORY i WHERE i.QTY_ON_HAND - i.QTY_RESERVED < 100");

    enum Params { NONE, CUSTOMER_ID, TIMESTAMP }

    static final List<Params> SELECT_PARAMS = List.of(Params.NONE, Params.NONE, Params.CUSTOMER_ID, Params.CUSTOMER_ID, Params.TIMESTAMP, Params.CUSTOMER_ID, Params.NONE);
    static final String UPDATE = "UPDATE INVENTORY SET UPDATED_AT = CURRENT_TIMESTAMP WHERE PRODUCT_ID = ?";

    @Test
    @Order(1)
    void twenty_logical_connections_run_fifty_statements_each_over_at_most_four_physical_connections() throws Exception {
        Stack s = Stack.current();
        s.ensureGateway();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicLong statements = new AtomicLong();
        AtomicLong rows = new AtomicLong();
        CountDownLatch holdOpen = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(CONNECTIONS);
        int physicalMax;
        long elapsedMs;
        try (Connection superuser = s.pgSuperuser("postgres");
             BackendSampler sampler = new BackendSampler(superuser, "sales_app", "sales");
             ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            long t0 = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < CONNECTIONS; w++) {
                final int worker = w;
                futures.add(vt.submit(() -> {
                    try (Connection c = s.connect(DS_SALES, BATCH)) {
                        c.setClientInfo("ApplicationName", BATCH);
                        for (int i = 0; i < STATEMENTS; i++) {
                            int idx = (worker + i) % SELECTS.size();
                            if (i > 0 && i % 10 == 0) {
                                c.setAutoCommit(false);
                                try (PreparedStatement ps = c.prepareStatement(UPDATE)) {
                                    for (int k = 0; k < 5; k++) {
                                        ps.setLong(1, 1 + (worker * 5 + k) % 60);
                                        ps.addBatch();
                                    }
                                    ps.executeBatch();
                                }
                                c.commit();
                                c.setAutoCommit(true);
                                statements.incrementAndGet();
                                continue;
                            }
                            try (PreparedStatement ps = c.prepareStatement(SELECTS.get(idx))) {
                                switch (SELECT_PARAMS.get(idx)) {
                                    case CUSTOMER_ID -> ps.setLong(1, 1 + (worker * 7 + i) % 200);
                                    case TIMESTAMP -> ps.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(365))));
                                    default -> { }
                                }
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        rows.incrementAndGet();
                                    }
                                }
                                statements.incrementAndGet();
                            }
                        }
                        allDone.countDown();
                        holdOpen.await(60, TimeUnit.SECONDS);   // keep the logical connection open so the pool stats show 20 logical sessions
                    } catch (Throwable e) {
                        errors.add(e);
                        allDone.countDown();
                    }
                    return null;
                }));
            }
            assertThat(allDone.await(300, TimeUnit.SECONDS)).as("all workers finished their statements").isTrue();
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            JsonNode health = s.gatewayAdmin("/health");
            int logical = health.path("logicalSessions").asInt();
            JsonNode poolStats = Await.until("/stats/pools reports the batch's logical sessions", Duration.ofSeconds(20), () ->
                    ControlPlaneApi.items(s.cp().get("/stats/pools").json())
                            .filter(p -> Stack.GATEWAY_ID.equals(p.path("gatewayId").asText()) && DS_SALES.equals(p.path("datasourceName").asText()))
                            .filter(p -> p.path("logicalSessions").asInt() >= CONNECTIONS).findFirst());
            holdOpen.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            assertThat(errors).as("errors: " + errors).isEmpty();
            assertThat(statements.get()).isEqualTo((long) CONNECTIONS * STATEMENTS);
            assertThat(sampler.failure()).isNull();
            physicalMax = sampler.max();
            assertThat(physicalMax).as("physical sales_app backends during the batch").isBetween(1, Stack.SALES_MAX_CONNECTIONS);
            assertThat(logical).as("logical sessions while all 20 batch connections were open").isGreaterThanOrEqualTo(CONNECTIONS);
            assertThat(poolStats.path("total").asInt()).isLessThanOrEqualTo(Stack.SALES_MAX_CONNECTIONS);
            double perSec = statements.get() * 1000.0 / Math.max(1, elapsedMs);
            Results.note("%d statements (%d rows) in %d ms = %.0f statements/s; physical max %d (cap %d); gateway logicalSessions=%d; /stats/pools logicalSessions=%d total=%d max=%d",
                    statements.get(), rows.get(), elapsedMs, perSec, physicalMax, Stack.SALES_MAX_CONNECTIONS, logical,
                    poolStats.path("logicalSessions").asInt(), poolStats.path("total").asInt(), poolStats.path("max").asInt());
        }
        Await.until("batch logical sessions closed", Duration.ofSeconds(15), () -> s.gatewayAdmin("/health").path("logicalSessions").asInt() == 0);
    }
}
