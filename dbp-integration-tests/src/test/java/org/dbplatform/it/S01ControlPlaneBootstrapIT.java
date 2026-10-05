package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.ControlPlaneApi.items;
import static org.dbplatform.it.support.Stack.BATCH;
import static org.dbplatform.it.support.Stack.CRED_APP;
import static org.dbplatform.it.support.Stack.DB_ALT;
import static org.dbplatform.it.support.Stack.DB_PG;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.LEGACY;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 1: control plane process, bootstrap import, api keys, datasource resolution for the gateway. */
@ExtendWith(ItExtension.class)
@Order(1)
@DisplayName("1 Control-plane bootstrap")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S01ControlPlaneBootstrapIT {

    @Test
    @Order(1)
    void control_plane_is_healthy_and_internal_endpoints_require_the_service_token() {
        Stack s = Stack.current();
        ControlPlaneApi cp = s.cp();
        JsonNode health = cp.getAbsolute(cp.baseUrl() + "/actuator/health").json();
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(cp.get("/internal/config-version").status()).as("internal endpoint without token").isEqualTo(401);
        JsonNode v = cp.internalGet("/internal/config-version").json();
        assertThat(v.path("configVersion").asLong()).isGreaterThanOrEqualTo(0);
        Results.note("control plane on port %d, configVersion %d, dev profile (H2 file store), DBP_DEMO_SEED_ENABLED=false", s.controlPlanePort(), v.path("configVersion").asLong());
        s.findings.forEach(Results::note);
    }

    @Test
    @Order(2)
    void import_of_the_adapted_bootstrap_document_upserts_the_whole_configuration() {
        Stack s = Stack.current();
        Stack.Bootstrap b = s.bootstrap();
        JsonNode r = b.importResult();
        assertThat(r.path("ok").asBoolean()).isTrue();
        JsonNode imported = r.path("imported");
        assertThat(imported.path("teams").asInt()).isEqualTo(2);
        assertThat(imported.path("applications").asInt()).isEqualTo(3);
        assertThat(imported.path("credentials").asInt()).isEqualTo(4);
        assertThat(imported.path("databases").asInt()).as("sales-oracle, sales-postgres, sales-alt").isEqualTo(3);
        assertThat(imported.path("datasources").asInt()).isEqualTo(3);
        assertThat(imported.path("accessGrants").asInt()).isEqualTo(5);

        JsonNode pg = ControlPlaneApi.findByField(s.cp().get("/databases").json(), "name", DB_PG).orElseThrow();
        assertThat(pg.path("engine").asText()).isEqualTo("POSTGRES");
        assertThat(pg.path("host").asText()).isEqualTo("127.0.0.1");
        assertThat(pg.path("port").asInt()).isEqualTo(s.pg().port());
        assertThat(pg.path("serviceName").asText()).isEqualTo("sales");
        assertThat(ControlPlaneApi.findByField(s.cp().get("/databases").json(), "name", DB_ALT)).isPresent();

        JsonNode sales = ControlPlaneApi.findByField(s.cp().get("/datasources").json(), "name", DS_SALES).orElseThrow();
        assertThat(sales.path("currentDatabaseId").asText()).isEqualTo(s.id("db", DB_PG));
        assertThat(sales.path("poolPolicy").path("maxConnections").asInt()).isEqualTo(Stack.SALES_MAX_CONNECTIONS);
        assertThat(sales.path("poolPolicy").path("mode").asText()).isEqualTo("TRANSACTION");

        JsonNode cred = ControlPlaneApi.findByField(s.cp().get("/credentials").json(), "name", CRED_APP).orElseThrow();
        assertThat(cred.path("provider").asText()).isEqualTo("INLINE");
        assertThat(cred.path("username").asText()).isEqualTo("sales_app");
        assertThat(cred.has("secret")).as("secret never returned").isFalse();
        assertThat(cred.has("encryptedSecret")).as("ciphertext never returned").isFalse();
        Results.note("import counts: %s", imported.toString());
        Results.note("sales-postgres -> jdbc:postgresql://127.0.0.1:%d/sales, sales.poolPolicy.maxConnections=%d, credential INLINE (AES-GCM under DBP_MASTER_KEY)", s.pg().port(), Stack.SALES_MAX_CONNECTIONS);
    }

    @Test
    @Order(3)
    void api_keys_are_issued_once_and_authenticate_the_application() {
        Stack s = Stack.current();
        s.bootstrap();
        for (String app : new String[] {ORDERS, BATCH}) {
            String key = s.apiKey(app);
            assertThat(key).matches("dbp_[A-Za-z0-9]{8}_[A-Za-z0-9]{16,}");
            JsonNode keys = s.cp().get("/applications/" + s.id("app", app) + "/api-keys").json();
            assertThat(items(keys).count()).isGreaterThanOrEqualTo(1);
            items(keys).forEach(k -> {
                assertThat(k.has("apiKey")).as("plaintext never listed").isFalse();
                assertThat(key).startsWith("dbp_" + k.path("prefix").asText() + "_");
            });
            // without the service token the internal endpoint is refused, with it the key resolves the application
            s.cp().post("/internal/auth/application", Map.of("apiKey", key)).expect(401, 403);
            ControlPlaneApi.Response r = authenticate(s, key);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().path("applicationId").asText()).isEqualTo(s.id("app", app));
            assertThat(r.body().path("name").asText()).isEqualTo(app);
        }
        assertThat(authenticate(s, "dbp_00000000_notakey").status()).isEqualTo(401);
    }

    private static ControlPlaneApi.Response authenticate(Stack s, String key) {
        return s.cp().internalPost("/internal/auth/application", Map.of("apiKey", key));
    }

    @Test
    @Order(4)
    void resolve_returns_the_postgres_jdbc_url_grant_and_pool_policy() {
        Stack s = Stack.current();
        s.bootstrap();
        String path = "/internal/resolve/datasource/" + DS_SALES + "?applicationId=" + s.id("app", ORDERS);
        assertThat(s.cp().get(path).status()).as("resolve without service token").isEqualTo(401);
        JsonNode r = s.cp().internalGet(path).json();
        assertThat(r.path("datasource").path("name").asText()).isEqualTo(DS_SALES);
        assertThat(r.path("database").path("name").asText()).isEqualTo(DB_PG);
        assertThat(r.path("database").path("engine").asText()).isEqualTo("POSTGRES");
        assertThat(r.path("database").path("jdbcUrl").asText()).isEqualTo("jdbc:postgresql://127.0.0.1:" + s.pg().port() + "/sales");
        assertThat(r.path("database").path("jdbcProperties").path("escapeSyntaxCallMode").asText()).isEqualTo("callIfNoReturn");
        assertThat(r.path("credential").path("username").asText()).isEqualTo("sales_app");
        assertThat(r.path("credential").path("version").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(r.path("poolPolicy").path("maxConnections").asInt()).isEqualTo(Stack.SALES_MAX_CONNECTIONS);
        assertThat(r.path("poolPolicy").path("mode").asText()).isEqualTo("TRANSACTION");
        assertThat(r.path("grant").path("maxLogicalConnections").asInt()).isEqualTo(50);
        assertThat(r.path("grant").path("readOnly").asBoolean()).isFalse();
        assertThat(r.path("grant").path("poolMode").asText()).isEqualTo("TRANSACTION");
        assertThat(r.path("configVersion").asLong()).isGreaterThan(0);

        JsonNode legacy = s.cp().internalGet("/internal/resolve/datasource/" + DS_SALES + "?applicationId=" + s.id("app", LEGACY)).json();
        assertThat(legacy.path("grant").path("readOnly").asBoolean()).as("legacy-reporting grant is read-only").isTrue();
        assertThat(s.cp().internalGet("/internal/resolve/datasource/nosuch?applicationId=" + s.id("app", ORDERS)).status()).isEqualTo(404);
        Results.note("resolve: jdbcUrl=%s, credential v%d, poolPolicy %s", r.path("database").path("jdbcUrl").asText(),
                r.path("credential").path("version").asInt(), r.path("poolPolicy").toString());
    }

    @Test
    @Order(5)
    void test_connection_and_credential_material_use_the_stored_secret() {
        Stack s = Stack.current();
        s.bootstrap();
        JsonNode t = s.cp().post("/databases/" + s.id("db", DB_PG) + "/test-connection", null).json();
        assertThat(t.path("ok").asBoolean()).as(t.toString()).isTrue();
        assertThat(t.path("productName").asText()).containsIgnoringCase("PostgreSQL");
        String credId = s.id("cred", CRED_APP);
        JsonNode material = s.cp().internalGet("/internal/credentials/" + credId + "/material").json();
        assertThat(material.path("username").asText()).isEqualTo("sales_app");
        assertThat(material.path("secret").asText()).isEqualTo(Stack.SALES_APP_PASSWORD);
        assertThat(s.cp().get("/internal/credentials/" + credId + "/material").status()).isEqualTo(401);
        Results.note("test-connection: %s %s in %s ms", t.path("productName").asText(), t.path("productVersion").asText(), t.path("latencyMs").asText());
    }
}
