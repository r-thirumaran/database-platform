package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Datasource resolution precedence and the internal proxy configuration. */
class ResolutionApiTest extends AbstractApiTest {

    @Test
    void resolutionPrecedenceApplicationRuleThenTagThenCurrent() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String credId = credential(uniq("cred"), "INLINE", null, "pw").get("id").asText();
        String ora = database(uniq("ora"), "ORACLE", "oracle", 1521, "FREEPDB1", credId, List.of("SALES")).get("id").asText();
        String pg = database(uniq("pg"), "POSTGRES", "postgres", 5432, "sales", credId, List.of("sales")).get("id").asText();
        String ms = database(uniq("ms"), "MSSQL", "mssql", 1433, "sales", credId, List.of("dbo")).get("id").asText();
        String pinned = application(uniq("pinned"), teamId, "SERVICE", Map.of("tags", List.of("pg-ready"))).get("id").asText();
        String tagged = application(uniq("tagged"), teamId, "BATCH", Map.of("tags", List.of("pg-ready"))).get("id").asText();
        String plain = application(uniq("plain"), teamId, "UI", Map.of()).get("id").asText();
        String nogrant = application(uniq("nogrant"), teamId, "UI", Map.of()).get("id").asText();
        String dsName = uniq("sales");
        JsonNode ds = datasource(dsName, teamId, ora, pg, List.of(
                Map.of("priority", 20, "tag", "pg-ready", "databaseId", pg, "readOnly", false),
                Map.of("priority", 10, "applicationId", pinned, "databaseId", ms, "readOnly", true)));
        for (String app : List.of(pinned, tagged, plain)) grant(app, ds.get("id").asText(), Map.of("maxLogicalConnections", 7));
        grant(nogrant, ds.get("id").asText(), Map.of("enabled", false));

        // application-specific rule wins
        JsonNode r1 = internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + pinned, 200);
        assertThat(r1.get("database").get("id").asText()).isEqualTo(ms);
        assertThat(r1.get("database").get("jdbcUrl").asText()).isEqualTo("jdbc:sqlserver://mssql:1433;databaseName=sales;encrypt=false");
        assertThat(r1.get("grant").get("readOnly").asBoolean()).isTrue();
        assertThat(r1.get("grant").get("maxLogicalConnections").asInt()).isEqualTo(7);
        assertThat(r1.get("grant").get("poolMode").asText()).isEqualTo("TRANSACTION");
        assertThat(r1.get("credential").get("id").asText()).isEqualTo(credId);
        assertThat(r1.get("credential").get("version").asInt()).isEqualTo(1);
        assertThat(r1.get("configVersion").asLong()).isPositive();
        assertThat(r1.get("poolPolicy").get("maxSize").asInt()).isEqualTo(12);
        assertThat(r1.get("poolPolicy").get("maxConnections").asInt()).isEqualTo(12);
        assertThat(r1.get("poolPolicy").get("mode").asText()).isEqualTo("TRANSACTION");
        assertThat(r1.get("datasource").get("state").asText()).isEqualTo("MIGRATING");
        // tag rule
        JsonNode r2 = internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + tagged, 200);
        assertThat(r2.get("database").get("id").asText()).isEqualTo(pg);
        assertThat(r2.get("database").get("jdbcUrl").asText()).isEqualTo("jdbc:postgresql://postgres:5432/sales");
        // default: currentDatabaseId
        JsonNode r3 = internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + plain, 200);
        assertThat(r3.get("database").get("id").asText()).isEqualTo(ora);
        assertThat(r3.get("database").get("jdbcUrl").asText()).isEqualTo("jdbc:oracle:thin:@//oracle:1521/FREEPDB1");
        assertThat(r3.get("database").get("engine").asText()).isEqualTo("ORACLE");
        // no enabled grant → 403; unknown datasource → 404; static mode (no applicationId) → current database
        internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + nogrant, 403);
        internalGet("/api/v1/internal/resolve/datasource/nope-" + dsName, 404);
        assertThat(internalGet("/api/v1/internal/resolve/datasource/" + dsName, 200).get("database").get("id").asText()).isEqualTo(ora);

        // pool mode override on the grant wins over the datasource policy
        JsonNode g = getJson("/api/v1/access-grants?applicationId=" + plain + "&datasourceId=" + ds.get("id").asText(), 200).get(0);
        putJson("/api/v1/access-grants/" + g.get("id").asText(), Map.of("applicationId", plain, "datasourceId", ds.get("id").asText(), "poolModeOverride", "SESSION"), 200);
        assertThat(internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + plain, 200).get("grant").get("poolMode").asText()).isEqualTo("SESSION");

        // credential rotation surfaces in the resolution
        postJson("/api/v1/credentials/" + credId + "/rotate", Map.of("secret", "pw2"), 200);
        assertThat(internalGet("/api/v1/internal/resolve/datasource/" + dsName + "?applicationId=" + plain, 200).get("credential").get("version").asInt()).isEqualTo(2);

        // proxy config lists the datasource as a route of the engine listener and the application identity rules
        JsonNode cfg = internalGet("/api/v1/internal/proxy/config?proxyId=proxy-1", 200);
        assertThat(cfg.get("configVersion").asLong()).isPositive();
        JsonNode oracleListener = null;
        for (JsonNode l : cfg.get("listeners")) if (l.get("engine").asText().equals("ORACLE")) oracleListener = l;
        assertThat(oracleListener).isNotNull();
        assertThat(oracleListener.get("port").asInt()).isEqualTo(1521);
        assertThat(oracleListener.get("defaultRoute").get("rewriteServiceName").asBoolean()).isFalse();
        boolean routeFound = false;
        for (JsonNode r : oracleListener.get("routes")) {
            if (r.get("match").asText().equals(dsName)) {
                routeFound = true;
                assertThat(r.get("databaseId").asText()).isEqualTo(ora);
                assertThat(r.get("serviceName").asText()).isEqualTo("FREEPDB1");
                assertThat(r.get("rewriteServiceName").asBoolean()).isTrue();
            }
        }
        assertThat(routeFound).isTrue();
        boolean appFound = false;
        for (JsonNode a : cfg.get("applications")) if (a.get("id").asText().equals(pinned)) { appFound = true; assertThat(a.get("identityRules").has("programs")).isTrue(); assertThat(a.get("identityRules").has("programNames")).isTrue(); }
        assertThat(appFound).isTrue();
        boolean quota = false;
        for (JsonNode q : cfg.get("quotas")) if (q.get("applicationId").asText().equals(pinned) && q.get("datasourceId").asText().equals(ds.get("id").asText())) quota = q.get("maxProxyConnections").asInt() == 10;
        assertThat(quota).isTrue();
    }
}
