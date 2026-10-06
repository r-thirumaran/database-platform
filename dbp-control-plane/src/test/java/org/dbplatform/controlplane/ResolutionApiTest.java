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
        for (JsonNode a : cfg.get("applications")) if (a.get("id").asText().equals(pinned)) { appFound = true; assertThat(a.get("identityRules").has("programNames")).isTrue(); assertThat(a.get("identityRules").has("programs")).isFalse(); }
        assertThat(appFound).isTrue();
        boolean quota = false;
        for (JsonNode q : cfg.get("quotas")) if (q.get("applicationId").asText().equals(pinned) && q.get("datasourceId").asText().equals(ds.get("id").asText())) quota = q.get("maxProxyConnections").asInt() == 10;
        assertThat(quota).isTrue();
    }

    /**
     * The proxy's Jackson record accepts the dbp-common spelling as an alias of the public one, but rejects a document that
     * carries both ("Should never call set() on setterless property"). The proxy configuration must therefore contain exactly
     * one spelling per identity rule - the public API spelling.
     */
    @Test
    void proxyConfigEmitsExactlyOneSpellingPerIdentityRule() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        // unique values: the Spring context is shared and RuntimeMergerTest attributes sessions through every application's rules
        String tag = uniq("spelling");
        String withRules = application(uniq("rules"), teamId, "SERVICE", Map.of("identityRules", Map.of(
                "serviceAliases", List.of(tag + "-alias"),
                "programNames", List.of(tag + "-program", "JDBC Thin Client/" + tag),
                "pgApplicationNames", List.of(tag + "-pg"),
                "machinePatterns", List.of(tag + "-host-*"),
                "cidrs", List.of("203.0.113.0/24")))).get("id").asText();
        String withoutRules = application(uniq("bare"), teamId, "UI", Map.of()).get("id").asText();

        JsonNode cfg = internalGet("/api/v1/internal/proxy/config?proxyId=proxy-spelling", 200);
        List<String> expectedKeys = List.of("serviceAliases", "programNames", "pgApplicationNames", "machinePatterns", "cidrs");
        List<String> oldSpellings = List.of("programs", "machines", "applicationNames");
        int checked = 0;
        for (JsonNode app : cfg.get("applications")) {
            JsonNode rules = app.get("identityRules");
            List<String> keys = new java.util.ArrayList<>();
            rules.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("identityRules keys of application %s", app.get("name").asText()).containsExactlyInAnyOrderElementsOf(expectedKeys);
            for (String old : oldSpellings) assertThat(rules.has(old)).as("%s must not be emitted next to the public spelling", old).isFalse();
            for (String key : expectedKeys) assertThat(rules.get(key).isArray()).as(key).isTrue();
            checked++;
            if (app.get("id").asText().equals(withRules)) {
                assertThat(strings(rules.get("serviceAliases"))).containsExactly(tag + "-alias");
                assertThat(strings(rules.get("programNames"))).containsExactly(tag + "-program", "JDBC Thin Client/" + tag);
                assertThat(strings(rules.get("pgApplicationNames"))).containsExactly(tag + "-pg");
                assertThat(strings(rules.get("machinePatterns"))).containsExactly(tag + "-host-*");
                assertThat(strings(rules.get("cidrs"))).containsExactly("203.0.113.0/24");
            }
            if (app.get("id").asText().equals(withoutRules)) {
                for (String key : expectedKeys) assertThat(rules.get(key)).as(key).isEmpty();
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(2);
        // the raw document must not contain the old spellings anywhere in the applications array
        String raw = cfg.get("applications").toString();
        for (String old : oldSpellings) assertThat(raw).doesNotContain("\"" + old + "\"");
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** D5: H2 and OTHER databases can be created and resolved; they have no proxy listener. */
    @Test
    void h2AndOtherEnginesResolveAndStayOutOfTheProxyConfig() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String credId = credential(uniq("cred"), "INLINE", null, "pw").get("id").asText();

        // H2 TCP server: POST /databases engine H2 -> 201, /internal/resolve returns jdbc:h2:tcp://host:port/serviceName
        JsonNode h2 = database(uniq("h2"), "H2", "h2-host", 9092, "mem:salesh2", credId, List.of("PUBLIC"));
        assertThat(h2.get("engine").asText()).isEqualTo("H2");
        String h2Ds = uniq("h2ds");
        JsonNode ds = datasource(h2Ds, teamId, h2.get("id").asText(), null, null);
        String app = application(uniq("h2app"), teamId, "SERVICE", Map.of()).get("id").asText();
        grant(app, ds.get("id").asText(), Map.of());
        JsonNode resolved = internalGet("/api/v1/internal/resolve/datasource/" + h2Ds + "?applicationId=" + app, 200);
        assertThat(resolved.get("database").get("engine").asText()).isEqualTo("H2");
        assertThat(resolved.get("database").get("jdbcUrl").asText()).isEqualTo("jdbc:h2:tcp://h2-host:9092/mem:salesh2");
        assertThat(resolved.get("credential").get("id").asText()).isEqualTo(credId);

        // OTHER needs the complete URL in jdbcProperties.url: without it 400 with a clear message ...
        Map<String, Object> other = new java.util.HashMap<>(Map.of("name", uniq("other"), "engine", "OTHER", "host", "mariadb-host", "port", 3306,
                "serviceName", "sales", "credentialId", credId));
        JsonNode rejected = postJson("/api/v1/databases", other, 400);
        assertThat(rejected.toString()).contains("OTHER").contains("jdbcProperties.url");
        other.put("jdbcProperties", Map.of("url", "  "));
        postJson("/api/v1/databases", other, 400);
        // ... and with it the URL is used verbatim
        other.put("name", uniq("other"));
        other.put("jdbcProperties", Map.of("url", "jdbc:mariadb://mariadb-host:3306/sales", "useSSL", "false"));
        JsonNode otherDb = postJson("/api/v1/databases", other, 201);
        // an update that removes the url is rejected, too
        Map<String, Object> noUrl = new java.util.HashMap<>(other);
        noUrl.put("jdbcProperties", Map.of());
        putJson("/api/v1/databases/" + otherDb.get("id").asText(), noUrl, 400);
        String otherDs = uniq("otherds");
        JsonNode ods = datasource(otherDs, teamId, otherDb.get("id").asText(), null, null);
        grant(app, ods.get("id").asText(), Map.of());
        JsonNode otherResolved = internalGet("/api/v1/internal/resolve/datasource/" + otherDs + "?applicationId=" + app, 200);
        assertThat(otherResolved.get("database").get("engine").asText()).isEqualTo("OTHER");
        assertThat(otherResolved.get("database").get("jdbcUrl").asText()).isEqualTo("jdbc:mariadb://mariadb-host:3306/sales");

        // neither shows up in the proxy configuration: no listener for the engine, no route for the datasources
        JsonNode cfg = internalGet("/api/v1/internal/proxy/config?proxyId=proxy-h2", 200);
        for (JsonNode l : cfg.get("listeners")) {
            assertThat(l.get("engine").asText()).isIn("ORACLE", "POSTGRES", "MSSQL");
            assertThat(l.get("port").asInt()).isPositive();
            for (JsonNode r : l.get("routes")) assertThat(r.get("match").asText()).isNotIn(h2Ds, otherDs);
        }
    }
}
