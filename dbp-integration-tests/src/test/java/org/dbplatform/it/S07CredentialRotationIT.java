package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.ControlPlaneApi.stream;
import static org.dbplatform.it.support.Stack.CRED_APP;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 7: credential rotation drains the old pool while connections keep working. */
@ExtendWith(ItExtension.class)
@Order(7)
@DisplayName("7 Credential rotation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S07CredentialRotationIT {

    private static List<JsonNode> salesPools(Stack s) {
        return stream(s.gatewayAdmin("/pools")).filter(p -> stream(p.path("datasources")).anyMatch(d -> DS_SALES.equals(d.asText()))).toList();
    }

    @Test
    @Order(1)
    void rotation_bumps_the_version_drains_the_old_pool_and_keeps_traffic_flowing() throws Exception {
        Stack s = Stack.current();
        s.ensureGateway();
        try (Connection warm = s.connect(DS_SALES, ORDERS)) {
            Sql.queryLong(warm, "SELECT 1");
        }
        List<JsonNode> before = salesPools(s);
        assertThat(before).as("one warm pool for sales").hasSize(1);
        int v0 = before.get(0).path("credentialVersion").asInt();
        assertThat(before.get(0).path("draining").asBoolean()).isFalse();
        String credId = s.id("cred", CRED_APP);
        assertThat(s.cp().get("/credentials/" + credId).json().path("version").asInt()).isEqualTo(v0);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger statements = new AtomicInteger();
        AtomicInteger newConnections = new AtomicInteger();
        long end = System.currentTimeMillis() + 14_000;
        Thread worker = new Thread(() -> {
            try (Connection c = s.connect(DS_SALES, ORDERS)) {
                long nextNew = 0;
                while (System.currentTimeMillis() < end) {
                    Sql.queryLong(c, "SELECT count(*) FROM product");
                    statements.incrementAndGet();
                    if (System.currentTimeMillis() > nextNew) {
                        try (Connection fresh = s.connect(DS_SALES, ORDERS)) {
                            Sql.queryLong(fresh, "SELECT 1");
                            newConnections.incrementAndGet();
                        }
                        nextNew = System.currentTimeMillis() + 1000;
                    }
                    Thread.sleep(100);
                }
            } catch (Throwable e) {
                errors.add(e);
            }
        }, "rotation-traffic");
        worker.start();
        Thread.sleep(1500);
        JsonNode rotated = s.cp().post("/credentials/" + credId + "/rotate", Map.of("secret", Stack.SALES_APP_PASSWORD)).json();
        assertThat(rotated.path("version").asInt()).isEqualTo(v0 + 1);
        assertThat(rotated.has("secret")).isFalse();
        long t0 = System.nanoTime();
        List<JsonNode> after = Await.until("gateway pools switched to credential version " + (v0 + 1) + " and the old pool is gone", Duration.ofSeconds(45), () -> {
            List<JsonNode> pools = salesPools(s);
            boolean onlyNew = !pools.isEmpty() && pools.stream().allMatch(p -> p.path("credentialVersion").asInt() == v0 + 1 && !p.path("draining").asBoolean());
            return onlyNew ? Optional.of(pools) : Optional.empty();
        });
        long switchMs = (System.nanoTime() - t0) / 1_000_000;
        JsonNode stats = Await.until("/stats/pools reports credentialVersion " + (v0 + 1), Duration.ofSeconds(30), () ->
                ControlPlaneApi.items(s.cp().get("/stats/pools").json())
                        .filter(p -> Stack.GATEWAY_ID.equals(p.path("gatewayId").asText()) && DS_SALES.equals(p.path("datasourceName").asText()))
                        .filter(p -> p.path("credentialVersion").asInt() == v0 + 1).findFirst());
        worker.join(30_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(errors).as("errors while rotating: " + errors).isEmpty();
        assertThat(statements.get()).isGreaterThan(50);
        assertThat(newConnections.get()).isGreaterThanOrEqualTo(10);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("key").asText()).contains("v" + (v0 + 1));
        assertThat(s.salesAppBackends()).isLessThanOrEqualTo(Stack.SALES_MAX_CONNECTIONS);
        assertThat(s.gatewayProcess().logContains("draining (credential rotated)")).as("gateway logged the drain").isTrue();
        Results.note("credential %s v%d -> v%d; gateway switched pools in %d ms (old pool drained & closed), /stats/pools credentialVersion=%d; %d statements + %d new connections during rotation, 0 errors",
                CRED_APP, v0, v0 + 1, switchMs, stats.path("credentialVersion").asInt(), statements.get(), newConnections.get());
    }
}
