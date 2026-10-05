package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** CRUD for every configuration resource, api keys, validation/error body, service-token enforcement. */
class ConfigApiTest extends AbstractApiTest {

    @Test
    void teamCrudAndSummary() throws Exception {
        String name = uniq("team");
        JsonNode t = team(name);
        assertThat(t.get("id").asText()).isNotBlank();
        assertThat(t.get("createdAt").asText()).contains("T");
        String id = t.get("id").asText();
        JsonNode updated = putJson("/api/v1/teams/" + id, Map.of("name", name, "displayName", "Renamed", "tags", List.of("domain:x")), 200);
        assertThat(updated.get("displayName").asText()).isEqualTo("Renamed");
        assertThat(updated.get("tags").get(0).asText()).isEqualTo("domain:x");
        assertThat(getJson("/api/v1/teams", 200).isArray()).isTrue();
        JsonNode summary = getJson("/api/v1/teams/" + id + "/summary", 200);
        assertThat(summary.get("team").get("id").asText()).isEqualTo(id);
        assertThat(summary.has("applications") && summary.has("ownedTables") && summary.has("consumedTables") && summary.has("producedTables") && summary.has("datasourcesOwned")).isTrue();
        // duplicate name → 409 with the contract error body
        JsonNode err = postJson("/api/v1/teams", Map.of("name", name), 409);
        assertThat(err.get("status").asInt()).isEqualTo(409);
        assertThat(err.get("error").asText()).isEqualTo("CONFLICT");
        assertThat(err.get("path").asText()).isEqualTo("/api/v1/teams");
        deleteJson("/api/v1/teams/" + id, 204);
        JsonNode nf = getJson("/api/v1/teams/" + id, 404);
        assertThat(nf.get("error").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void validationErrors() throws Exception {
        JsonNode err = postJson("/api/v1/teams", Map.of("name", "has space!"), 400);
        assertThat(err.get("error").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("message").asText()).contains("name");
        JsonNode bad = postJson("/api/v1/applications", Map.of("name", uniq("app"), "kind", "NOT_A_KIND"), 400);
        assertThat(bad.get("error").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void applicationCrudWithIdentityRulesAndApiKeys() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        JsonNode app = application(uniq("orders"), teamId, "SERVICE", Map.of("runtime", "KUBERNETES",
                "identityRules", Map.of("cidrs", List.of("10.20.0.0/16"), "programNames", List.of("orders-service"), "serviceAliases", List.of("orders")),
                "tags", List.of("pg-ready")));
        String appId = app.get("id").asText();
        assertThat(app.get("identityRules").get("programNames").get(0).asText()).isEqualTo("orders-service");
        assertThat(app.get("identityRules").get("machinePatterns").isArray()).isTrue();
        // unknown team → 400
        postJson("/api/v1/applications", Map.of("name", uniq("x"), "kind", "SERVICE", "teamId", "nope"), 400);

        // api keys: plaintext once, prefix = first 8 chars of the key id, list never contains the secret
        JsonNode issued = postJson("/api/v1/applications/" + appId + "/api-keys", Map.of("label", "prod"), 201);
        String key = issued.get("apiKey").asText();
        assertThat(key).startsWith("dbp_" + issued.get("prefix").asText() + "_");
        assertThat(issued.get("prefix").asText()).isEqualTo(issued.get("id").asText().substring(0, 8));
        JsonNode list = getJson("/api/v1/applications/" + appId + "/api-keys", 200);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).has("apiKey")).isFalse();
        assertThat(list.get(0).has("keyHash")).isFalse();
        assertThat(list.get(0).get("label").asText()).isEqualTo("prod");

        // internal auth
        JsonNode identity = internalPost("/api/v1/internal/auth/application", Map.of("apiKey", key), 200);
        assertThat(identity.get("applicationId").asText()).isEqualTo(appId);
        assertThat(identity.get("teamId").asText()).isEqualTo(teamId);
        assertThat(identity.get("tags").get(0).asText()).isEqualTo("pg-ready");
        internalPost("/api/v1/internal/auth/application", Map.of("apiKey", "dbp_bogus_key"), 401);
        // revoke → 401
        deleteJson("/api/v1/applications/" + appId + "/api-keys/" + issued.get("id").asText(), 204);
        internalPost("/api/v1/internal/auth/application", Map.of("apiKey", key), 401);
        assertThat(getJson("/api/v1/applications/" + appId + "/api-keys", 200).get(0).get("revokedAt").isNull()).isFalse();

        JsonNode summary = getJson("/api/v1/applications/" + appId + "/summary", 200);
        assertThat(summary.get("application").get("id").asText()).isEqualTo(appId);
        assertThat(summary.get("team").get("id").asText()).isEqualTo(teamId);
        assertThat(summary.has("grants") && summary.has("reads") && summary.has("writes") && summary.has("calls") && summary.has("connections") && summary.has("queryStats")).isTrue();
        deleteJson("/api/v1/applications/" + appId, 204);
    }

    @Test
    void serviceTokenIsEnforcedOnInternalEndpoints() throws Exception {
        mvc.perform(get("/api/v1/internal/config-version")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        mvc.perform(get("/api/v1/internal/config-version").header("X-DBP-Service-Token", "wrong")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        JsonNode v = internalGet("/api/v1/internal/config-version", 200);
        assertThat(v.get("configVersion").asLong()).isPositive();
        // public endpoints need no token in security mode none
        getJson("/api/v1/teams", 200);
    }

    @Test
    void configVersionBumpsOnChanges() throws Exception {
        long before = internalGet("/api/v1/internal/config-version", 200).get("configVersion").asLong();
        team(uniq("bump"));
        long after = internalGet("/api/v1/internal/config-version", 200).get("configVersion").asLong();
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void credentialsNeverExposeSecretsAndRotate() throws Exception {
        JsonNode inline = credential(uniq("cred"), "INLINE", null, "s3cret");
        assertThat(inline.has("secret")).isFalse();
        assertThat(inline.has("encryptedSecret")).isFalse();
        assertThat(inline.get("version").asLong()).isEqualTo(1);
        String id = inline.get("id").asText();
        JsonNode material = internalGet("/api/v1/internal/credentials/" + id + "/material", 200);
        assertThat(material.get("secret").asText()).isEqualTo("s3cret");
        assertThat(material.get("username").asText()).isEqualTo("APP_USER");
        JsonNode rotated = postJson("/api/v1/credentials/" + id + "/rotate", Map.of("secret", "n3w"), 200);
        assertThat(rotated.get("version").asLong()).isEqualTo(2);
        assertThat(internalGet("/api/v1/internal/credentials/" + id + "/material", 200).get("secret").asText()).isEqualTo("n3w");
        // provider ENV reads the environment; a missing variable is a 409 with a clear message
        JsonNode env = credential(uniq("env"), "ENV", "DBP_TEST_SURELY_UNSET_" + System.nanoTime(), null);
        JsonNode err = internalGet("/api/v1/internal/credentials/" + env.get("id").asText() + "/material", 409);
        assertThat(err.get("message").asText()).contains("not set");
        // FILE provider
        java.nio.file.Path f = java.nio.file.Files.createTempFile("dbp-secret", ".txt");
        java.nio.file.Files.writeString(f, "from-file\n");
        JsonNode file = credential(uniq("file"), "FILE", f.toString(), null);
        assertThat(internalGet("/api/v1/internal/credentials/" + file.get("id").asText() + "/material", 200).get("secret").asText()).isEqualTo("from-file");
        // unsupported providers → 501
        JsonNode vault = credential(uniq("vault"), "VAULT", "secret/dbp", null);
        JsonNode ni = internalGet("/api/v1/internal/credentials/" + vault.get("id").asText() + "/material", 501);
        assertThat(ni.get("error").asText()).isEqualTo("NOT_IMPLEMENTED");
        // ENV without ref → 400
        postJson("/api/v1/credentials", Map.of("name", uniq("noref"), "provider", "ENV"), 400);
        deleteJson("/api/v1/credentials/" + id, 204);
    }

    @Test
    void databaseDatasourceGrantCrud() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String credId = credential(uniq("cred"), "INLINE", null, "pw").get("id").asText();
        JsonNode ora = database(uniq("ora"), "ORACLE", "oracle", 1521, "FREEPDB1", credId, List.of("SALES"));
        assertThat(ora.get("collector").get("schemas").get(0).asText()).isEqualTo("SALES");
        assertThat(ora.get("collector").get("dictionaryIntervalSeconds").asInt()).isEqualTo(3600);
        JsonNode pg = database(uniq("pg"), "POSTGRES", "postgres", 5432, "sales", credId, List.of("sales"));
        // credential in use → 409
        deleteJson("/api/v1/credentials/" + credId, 409);
        // update database
        JsonNode upd = putJson("/api/v1/databases/" + ora.get("id").asText(), Map.of("name", ora.get("name").asText(), "engine", "ORACLE", "host", "oracle2", "port", 1521,
                "serviceName", "FREEPDB1", "credentialId", credId, "jdbcProperties", Map.of("oracle.jdbc.ReadTimeout", "60000")), 200);
        assertThat(upd.get("host").asText()).isEqualTo("oracle2");
        assertThat(upd.get("jdbcProperties").get("oracle.jdbc.ReadTimeout").asText()).isEqualTo("60000");
        assertThat(getJson("/api/v1/databases/" + ora.get("id").asText() + "/schemas", 200).isArray()).isTrue();
        JsonNode status = getJson("/api/v1/databases/" + ora.get("id").asText() + "/collector-status", 200);
        assertThat(status.has("lastDictionaryRun") || status.get("lastDictionaryRun") == null).isTrue();
        assertThat(status.get("tablesSeen").asInt()).isZero();
        // test-connection against an unreachable host reports ok=false rather than failing
        JsonNode test = postJson("/api/v1/databases/" + ora.get("id").asText() + "/test-connection", Map.of(), 200);
        assertThat(test.get("ok").asBoolean()).isFalse();

        // datasource with routing rules
        String appId = application(uniq("app"), teamId, "SERVICE", Map.of()).get("id").asText();
        JsonNode ds = datasource(uniq("sales"), teamId, ora.get("id").asText(), pg.get("id").asText(),
                List.of(Map.of("priority", 10, "applicationId", appId, "databaseId", pg.get("id").asText(), "readOnly", true)));
        String dsId = ds.get("id").asText();
        assertThat(ds.get("routingRules")).hasSize(1);
        assertThat(ds.get("routingRules").get(0).get("id").asText()).isNotBlank();
        assertThat(ds.get("state").asText()).isEqualTo("MIGRATING");
        // rule management
        JsonNode added = postJson("/api/v1/datasources/" + dsId + "/routing-rules", Map.of("priority", 5, "tag", "pg-ready", "databaseId", pg.get("id").asText()), 201);
        assertThat(added.get("routingRules")).hasSize(2);
        assertThat(added.get("routingRules").get(0).get("priority").asInt()).isEqualTo(5);
        String ruleId = added.get("routingRules").get(0).get("id").asText();
        assertThat(deleteJson("/api/v1/datasources/" + dsId + "/routing-rules/" + ruleId, 200).get("routingRules")).hasSize(1);
        JsonNode replaced = putJson("/api/v1/datasources/" + dsId + "/routing-rules", List.of(), 200);
        assertThat(replaced.get("routingRules")).isEmpty();
        postJson("/api/v1/datasources/" + dsId + "/routing-rules", Map.of("priority", 1, "databaseId", "unknown-db", "tag", "x"), 400);
        // database referenced by datasource → 409
        deleteJson("/api/v1/databases/" + ora.get("id").asText(), 409);

        // grants
        JsonNode g = grant(appId, dsId, Map.of("readOnly", false, "poolModeOverride", "SESSION"));
        assertThat(g.get("enabled").asBoolean()).isTrue();
        postJson("/api/v1/access-grants", Map.of("applicationId", appId, "datasourceId", dsId), 409);
        assertThat(getJson("/api/v1/access-grants?applicationId=" + appId, 200)).hasSize(1);
        assertThat(getJson("/api/v1/access-grants?datasourceId=" + dsId + "&applicationId=" + appId, 200)).hasSize(1);
        JsonNode gu = putJson("/api/v1/access-grants/" + g.get("id").asText(), Map.of("applicationId", appId, "datasourceId", dsId, "maxLogicalConnections", 99, "enabled", false), 200);
        assertThat(gu.get("maxLogicalConnections").asInt()).isEqualTo(99);
        assertThat(gu.get("enabled").asBoolean()).isFalse();

        // switch records a migration event and clears target
        JsonNode switched = postJson("/api/v1/datasources/" + dsId + "/switch", Map.of("databaseId", pg.get("id").asText(), "by", "tester"), 200);
        assertThat(switched.get("currentDatabaseId").asText()).isEqualTo(pg.get("id").asText());
        assertThat(switched.hasNonNull("targetDatabaseId")).isFalse(); // cleared: JSON null (or absent)
        assertThat(switched.get("state").asText()).isEqualTo("ACTIVE");
        JsonNode events = getJson("/api/v1/migration-events?datasourceId=" + dsId, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("fromDatabaseId").asText()).isEqualTo(ora.get("id").asText());
        assertThat(events.get(0).get("by").asText()).isEqualTo("tester");
        assertThat(events.get(0).has("at")).isTrue();

        JsonNode summary = getJson("/api/v1/datasources/" + dsId + "/summary", 200);
        assertThat(summary.get("currentDatabase").get("id").asText()).isEqualTo(pg.get("id").asText());
        assertThat(summary.get("grants")).hasSize(1);
        assertThat(summary.get("consumers").get(0).get("id").asText()).isEqualTo(appId);
        assertThat(summary.get("warnings").isArray()).isTrue();

        deleteJson("/api/v1/access-grants/" + g.get("id").asText(), 204);
        deleteJson("/api/v1/datasources/" + dsId, 204);
        deleteJson("/api/v1/databases/" + ora.get("id").asText(), 204);
    }

    @Test
    void capacityWarningWhenPoolsExceedDatabaseBudget() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        JsonNode db = postJson("/api/v1/databases", Map.of("name", uniq("small"), "engine", "POSTGRES", "host", "pg", "port", 5432, "serviceName", "x", "maxPhysicalConnections", 5), 201);
        JsonNode ds = datasource(uniq("ds"), teamId, db.get("id").asText(), null, null);
        JsonNode summary = getJson("/api/v1/datasources/" + ds.get("id").asText() + "/summary", 200);
        assertThat(summary.get("warnings")).hasSize(1);
        assertThat(summary.get("warnings").get(0).asText()).contains("maxPhysicalConnections 5");
    }

    @Test
    void spaFallbackAndOpenApi() throws Exception {
        mvc.perform(get("/some/ui/route")).andExpect(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("<title>");
        });
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(404));
        mvc.perform(get("/v3/api-docs")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        mvc.perform(post("/api/v1/teams").contentType(MediaType.APPLICATION_JSON).content("{not json")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }
}
