package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.LEGACY;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 9: identity and authorisation at HELLO time (api keys, grants, revocation, read-only grants). */
@ExtendWith(ItExtension.class)
@Order(9)
@DisplayName("9 Access control")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S09AccessControlIT {

    private static SQLException connectFails(String url) {
        try (Connection c = DriverManager.getConnection(url)) {
            Sql.queryLong(c, "SELECT 1");
            throw new AssertionError("connection unexpectedly succeeded: " + url.replaceAll("apiKey=[^&]+", "apiKey=***"));
        } catch (SQLException e) {
            return e;
        }
    }

    @Test
    @Order(1)
    void application_without_a_grant_is_rejected_with_08004() {
        Stack s = Stack.current();
        s.ensureGateway();
        JsonNode app = s.cp().post("/applications", Map.of("name", "no-grant-app", "displayName", "No Grant", "kind", "TOOL", "runtime", "OTHER",
                "teamId", s.id("team", "sales-platform"), "description", "created by dbp-integration-tests", "tags", List.of())).expect(201, 200).body();
        String key = s.cp().post("/applications/" + app.path("id").asText() + "/api-keys", Map.of("label", "it")).json().path("apiKey").asText();
        SQLException e = connectFails(s.dbpUrl(DS_SALES) + "?apiKey=" + key);
        assertThat(e.getSQLState()).isEqualTo("08004");
        assertThat((Throwable) e).isInstanceOf(SQLNonTransientConnectionException.class);
        assertThat(e.getMessage()).containsIgnoringCase("not authorised");
        Results.note("no-grant-app (valid key, no grant): %s", Sql.describe(e));
    }

    @Test
    @Order(2)
    void wrong_or_missing_api_key_is_rejected_with_08004() {
        Stack s = Stack.current();
        SQLException wrong = connectFails(s.dbpUrl(DS_SALES) + "?apiKey=dbp_00000000_thisisnotavalidkey");
        assertThat(wrong.getSQLState()).isEqualTo("08004");
        SQLException missing = connectFails(s.dbpUrl(DS_SALES));
        assertThat(missing.getSQLState()).isEqualTo("08004");
        SQLException unknownDs = connectFails(s.dbpUrl("nosuchds") + "?apiKey=" + s.apiKey(ORDERS));
        assertThat(unknownDs.getSQLState()).isEqualTo("08004");
        Results.note("wrong key: %s; missing key: %s; unknown datasource: %s", wrong.getMessage(), missing.getMessage(), unknownDs.getMessage());
    }

    @Test
    @Order(3)
    void revoked_key_is_rejected_for_new_connections() throws Exception {
        Stack s = Stack.current();
        String appId = s.id("app", ORDERS);
        JsonNode issued = s.cp().post("/applications/" + appId + "/api-keys", Map.of("label", "it-revoke")).json();
        String key = issued.path("apiKey").asText();
        Connection existing = DriverManager.getConnection(s.dbpUrl(DS_SALES) + "?apiKey=" + key);
        assertThat(Sql.queryLong(existing, "SELECT 1")).isEqualTo(1);
        s.cp().delete("/applications/" + appId + "/api-keys/" + issued.path("id").asText()).expect(204, 200);
        JsonNode listed = s.cp().get("/applications/" + appId + "/api-keys").json();
        assertThat(org.dbplatform.it.support.ControlPlaneApi.items(listed).filter(k -> issued.path("id").asText().equals(k.path("id").asText()))
                .allMatch(k -> !k.path("revokedAt").isNull())).isTrue();
        long t0 = System.nanoTime();
        SQLException rejected = Await.until("new connection with the revoked key is rejected", Duration.ofSeconds(30), () -> {
            try (Connection c = DriverManager.getConnection(s.dbpUrl(DS_SALES) + "?apiKey=" + key)) {
                Sql.queryLong(c, "SELECT 1");
                return Optional.empty();
            } catch (SQLException e) {
                return Optional.of(e);
            }
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(rejected.getSQLState()).isEqualTo("08004");
        boolean existingStillWorks;
        try {
            existingStillWorks = Sql.queryLong(existing, "SELECT 1") == 1;
        } catch (SQLException e) {
            existingStillWorks = false;
        } finally {
            existing.close();
        }
        Results.note("revoked key rejected for new connections after %d ms (auth cache invalidated by the configVersion bump); the session opened before revocation %s",
                ms, existingStillWorks ? "kept working (authentication is at HELLO time only)" : "was closed");
    }

    @Test
    @Order(4)
    void read_only_grant_blocks_writes() throws Exception {
        Stack s = Stack.current();
        String legacyId = s.id("app", LEGACY);
        String key = s.cp().post("/applications/" + legacyId + "/api-keys", Map.of("label", "it")).json().path("apiKey").asText();
        try (Connection c = DriverManager.getConnection(s.dbpUrl(DS_SALES) + "?apiKey=" + key)) {
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer")).isGreaterThan(0);
            String autocommit = attemptUpdate(c);
            c.setAutoCommit(false);
            String inTransaction = attemptUpdate(c);
            c.rollback();
            c.setAutoCommit(true);
            Results.note("legacy-reporting (readOnly grant): SELECT ok; UPDATE with autocommit -> %s; UPDATE inside an explicit transaction -> %s", autocommit, inTransaction);
            if (!autocommit.startsWith("blocked") || !inTransaction.startsWith("blocked")) {
                Results.partial("read-only grant is not enforced for " + (autocommit.startsWith("blocked") ? "" : "autocommit statements")
                        + (!autocommit.startsWith("blocked") && !inTransaction.startsWith("blocked") ? " nor " : "")
                        + (inTransaction.startsWith("blocked") ? "" : "statements inside explicit transactions")
                        + " (pgjdbc setReadOnly(true) with the default readOnlyMode=transaction only marks explicit BEGINs read only)");
            }
        }
    }

    private static String attemptUpdate(Connection c) {
        try {
            int n = Sql.update(c, "UPDATE customer SET status = status WHERE id = 1");
            return "allowed (" + n + " row)";
        } catch (SQLException e) {
            return "blocked " + e.getSQLState();
        }
    }
}
