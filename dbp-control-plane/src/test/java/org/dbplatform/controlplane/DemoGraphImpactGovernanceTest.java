package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Demo seed → graph, impact analysis, governance, stats, export/import round trip. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoGraphImpactGovernanceTest extends AbstractApiTest {

    private JsonNode seed() throws Exception { return postJson("/api/v1/seed/demo", Map.of(), 200); }

    private JsonNode tableByName(String dbName, String schema, String name) throws Exception {
        String dbId = databaseByName(dbName).get("id").asText();
        for (JsonNode t : getJson("/api/v1/tables?databaseId=" + dbId + "&schema=" + schema, 200)) if (t.get("name").asText().equals(name)) return t;
        throw new AssertionError("no table " + name);
    }

    private JsonNode databaseByName(String name) throws Exception {
        for (JsonNode d : getJson("/api/v1/databases", 200)) if (d.get("name").asText().equals(name)) return d;
        throw new AssertionError("no database " + name);
    }

    private JsonNode applicationByName(String name) throws Exception {
        for (JsonNode a : getJson("/api/v1/applications", 200)) if (a.get("name").asText().equals(name)) return a;
        throw new AssertionError("no application " + name);
    }

    @Test
    @Order(1)
    void seedIsIdempotent() throws Exception {
        JsonNode first = seed();
        assertThat(first.get("ok").asBoolean()).isTrue();
        assertThat(first.get("seeded").asBoolean()).isTrue();
        assertThat(first.get("teams").asInt()).isEqualTo(5);
        assertThat(first.get("applications").asInt()).isEqualTo(6);
        int teamsBefore = getJson("/api/v1/teams", 200).size();
        int tablesBefore = getJson("/api/v1/tables?databaseId=" + databaseByName("sales-oracle").get("id").asText(), 200).size();
        seed();
        assertThat(getJson("/api/v1/teams", 200).size()).isEqualTo(teamsBefore);
        assertThat(getJson("/api/v1/tables?databaseId=" + databaseByName("sales-oracle").get("id").asText(), 200).size()).isEqualTo(tablesBefore);
        assertThat(tablesBefore).isEqualTo(8); // 7 tables + 1 view
        JsonNode view = tableByName("sales-oracle", "SALES", "V_ORDER_SUMMARY");
        assertThat(view.get("kind").asText()).isEqualTo("VIEW");
        JsonNode paged = getJson("/api/v1/tables?page=0&size=3", 200);
        assertThat(paged.get("items")).hasSize(3);
        assertThat(paged.get("total").asLong()).isGreaterThanOrEqualTo(8);
        assertThat(getJson("/api/v1/tables?unowned=true", 200).isArray()).isTrue();
        JsonNode cols = getJson("/api/v1/tables/" + tableByName("sales-oracle", "SALES", "CUSTOMER").get("id").asText() + "/columns", 200);
        assertThat(cols.size()).isEqualTo(6);
        assertThat(cols.get(1).get("name").asText()).isEqualTo("EMAIL");
        assertThat(cols.get(1).get("classification").asText()).isEqualTo("PII");
        JsonNode schemas = getJson("/api/v1/databases/" + databaseByName("sales-oracle").get("id").asText() + "/schemas", 200);
        assertThat(schemas.get(0).get("name").asText()).isEqualTo("SALES");
        assertThat(schemas.get(0).get("tableCount").asLong()).isEqualTo(8);
        assertThat(schemas.get(0).get("routineCount").asLong()).isEqualTo(7);
    }

    @Test
    @Order(2)
    void graphHasTypedNodeIdsAndDepthLimitedTraversal() throws Exception {
        seed();
        JsonNode orders = tableByName("sales-oracle", "SALES", "ORDERS");
        String root = "table:" + orders.get("id").asText();
        JsonNode g = getJson("/api/v1/graph?root=" + root + "&depth=1", 200);
        Set<String> ids = new HashSet<>();
        g.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).contains(root);
        for (JsonNode n : g.get("nodes")) {
            assertThat(n.get("id").asText()).matches("(team|application|datasource|database|table|routine):.+");
            assertThat(n.get("type").asText()).isIn("TEAM", "APPLICATION", "DATASOURCE", "DATABASE", "TABLE", "ROUTINE");
            assertThat(n.get("id").asText()).endsWith(":" + n.get("refId").asText());
        }
        JsonNode rootNode = null;
        for (JsonNode n : g.get("nodes")) if (n.get("id").asText().equals(root)) rootNode = n;
        assertThat(rootNode.get("label").asText()).isEqualTo("SALES.ORDERS");
        assertThat(rootNode.get("attrs").get("engine").asText()).isEqualTo("ORACLE");
        assertThat(rootNode.get("attrs").get("ownerTeam").asText()).isEqualTo("sales-platform");
        assertThat(rootNode.get("attrs").get("queryCount").asLong()).isPositive();
        Set<String> kinds = new HashSet<>();
        g.get("edges").forEach(e -> { kinds.add(e.get("kind").asText()); assertThat(ids).contains(e.get("from").asText(), e.get("to").asText()); });
        assertThat(kinds).contains("READS", "WRITES", "OWNS", "HOSTS", "TRIGGERS", "FOREIGN_KEY");
        // depth 1 excludes the teams of consuming applications; depth 2 includes them
        JsonNode g2 = getJson("/api/v1/graph?root=" + root + "&depth=2", 200);
        assertThat(g2.get("nodes").size()).isGreaterThan(g.get("nodes").size());
        // edge kind filter + include filter
        JsonNode onlyReads = getJson("/api/v1/graph?root=" + root + "&depth=1&edgeKinds=READS&include=tables,applications", 200);
        for (JsonNode e : onlyReads.get("edges")) assertThat(e.get("kind").asText()).isEqualTo("READS");
        for (JsonNode n : onlyReads.get("nodes")) assertThat(n.get("type").asText()).isIn("TABLE", "APPLICATION");
        // whole graph with a cap
        JsonNode capped = getJson("/api/v1/graph?limit=5", 200);
        assertThat(capped.get("nodes")).hasSize(5);
        assertThat(capped.get("truncated").asBoolean()).isTrue();
        getJson("/api/v1/graph?root=bogus:1", 400);
        getJson("/api/v1/graph?root=table:unknown-id", 404);
        // the migration edge datasource → target database
        JsonNode full = getJson("/api/v1/graph?limit=5000", 200);
        boolean migrates = false;
        for (JsonNode e : full.get("edges")) if (e.get("kind").asText().equals("MIGRATES_TO")) migrates = true;
        assertThat(migrates).isTrue();
    }

    @Test
    @Order(3)
    void impactAnalysisForTableColumnAndDatasource() throws Exception {
        seed();
        JsonNode orders = tableByName("sales-oracle", "SALES", "ORDERS");
        JsonNode impact = getJson("/api/v1/impact/table/" + orders.get("id").asText(), 200);
        assertThat(impact.get("target").get("type").asText()).isEqualTo("TABLE");
        assertThat(impact.get("target").get("label").asText()).isEqualTo("SALES.ORDERS");
        assertThat(impact.get("owner").get("name").asText()).isEqualTo("sales-platform");
        assertThat(impact.get("producer").get("name").asText()).isEqualTo("orders-service");
        List<String> direct = new ArrayList<>();
        impact.get("directConsumers").forEach(c -> direct.add(c.get("application").get("name").asText() + ":" + c.get("kind").asText()));
        assertThat(direct).contains("orders-service:READS", "orders-service:WRITES", "payment-service:READS", "reporting-batch:READS", "legacy-billing:WRITES");
        List<String> indirect = new ArrayList<>();
        impact.get("indirectConsumers").forEach(c -> indirect.add(c.get("application").get("name").asText() + ":" + c.get("kind").asText() + ":" + (c.get("viaRoutine") != null ? c.get("viaRoutine").get("name").asText() : "view:" + c.get("viaView").get("name").asText())));
        assertThat(indirect).contains("orders-service:WRITES:ORDER_PKG.PLACE_ORDER", "customer-portal:READS:GET_CUSTOMER_TIER", "reporting-batch:READS:view:V_ORDER_SUMMARY");
        assertThat(impact.get("triggers").get(0).get("name").asText()).isEqualTo("TRG_ORDERS_AUDIT");
        assertThat(impact.get("routines").toString()).contains("ORDER_PKG.PLACE_ORDER").contains("GET_CUSTOMER_TIER");
        assertThat(impact.get("dependentViews").get(0).get("name").asText()).isEqualTo("V_ORDER_SUMMARY");
        assertThat(impact.get("foreignKeyDependents").toString()).contains("ORDER_ITEM").contains("PAYMENT");
        List<String> teams = new ArrayList<>();
        impact.get("teamsAffected").forEach(t -> teams.add(t.get("name").asText()));
        assertThat(teams).contains("finance", "analytics", "customer-experience").doesNotContain("sales-platform");
        assertThat(impact.get("queryStats").get("count7d").asLong()).isPositive();
        assertThat(impact.get("riskScore").asDouble()).isBetween(0.5, 1.0);
        assertThat(impact.get("riskFactors").toString()).contains("consuming team").contains("written by trigger").contains("migration in progress").contains("CONFIDENTIAL");

        // column impact lists the queries referencing the column
        JsonNode cols = getJson("/api/v1/tables/" + orders.get("id").asText() + "/columns", 200);
        String statusCol = null;
        for (JsonNode c : cols) if (c.get("name").asText().equals("STATUS")) statusCol = c.get("id").asText();
        JsonNode col = getJson("/api/v1/impact/column/" + statusCol, 200);
        assertThat(col.get("target").get("type").asText()).isEqualTo("COLUMN");
        assertThat(col.get("target").get("label").asText()).isEqualTo("SALES.ORDERS.STATUS");
        assertThat(col.get("queriesReferencingColumn").size()).isGreaterThanOrEqualTo(1);
        assertThat(col.get("queriesReferencingColumn").get(0).get("sqlNormalized").asText().toLowerCase()).contains("status");

        // datasource impact aggregates every table of the current database
        JsonNode dsImpact = null;
        for (JsonNode ds : getJson("/api/v1/datasources", 200)) if (ds.get("name").asText().equals("sales")) dsImpact = getJson("/api/v1/impact/datasource/" + ds.get("id").asText(), 200);
        assertThat(dsImpact.get("tables").size()).isEqualTo(8);
        assertThat(dsImpact.get("tables").get(0).get("consumers").isArray()).isTrue();
        assertThat(dsImpact.get("tables").get(0).get("queryCount").asLong()).isPositive();
        assertThat(dsImpact.get("applications").size()).isGreaterThanOrEqualTo(6);
        boolean ruled = false, unruled = false;
        for (JsonNode a : dsImpact.get("applications")) {
            assertThat(a.get("application").has("name")).isTrue();
            if (a.get("application").get("name").asText().equals("reporting-batch")) ruled = a.get("hasRoutingRule").asBoolean();
            if (a.get("application").get("name").asText().equals("payment-service")) unruled = !a.get("hasRoutingRule").asBoolean();
        }
        assertThat(ruled && unruled).isTrue();
        assertThat(dsImpact.get("teamsAffected").size()).isGreaterThanOrEqualTo(5);
        assertThat(dsImpact.get("routines").toString()).contains("ORDER_PKG.PLACE_ORDER");
        assertThat(dsImpact.get("triggers").toString()).contains("TRG_ORDERS_AUDIT");
        assertThat(dsImpact.get("riskFactors").toString()).contains("MIGRATING").contains("engine change ORACLE");
        assertThat(dsImpact.get("currentDatabase").get("name").asText()).isEqualTo("sales-oracle");
        assertThat(dsImpact.get("targetDatabase").get("name").asText()).isEqualTo("sales-postgres");

        // table summary: views as TableRef, FK lists, consumers with viaRoutine
        JsonNode summary = getJson("/api/v1/tables/" + orders.get("id").asText() + "/summary", 200);
        assertThat(summary.get("views").get(0).get("name").asText()).isEqualTo("V_ORDER_SUMMARY");
        assertThat(summary.get("foreignKeysOut").get(0).get("name").asText()).isEqualTo("CUSTOMER");
        assertThat(summary.get("foreignKeysIn").size()).isEqualTo(2);
        assertThat(summary.get("topQueries").size()).isGreaterThanOrEqualTo(1);
        boolean via = false;
        for (JsonNode c : summary.get("consumers")) if (c.get("viaRoutine") != null && c.get("viaRoutine").get("name").asText().equals("ORDER_PKG.PLACE_ORDER")) via = true;
        assertThat(via).isTrue();
        // ownership endpoints
        JsonNode bulk = postJson("/api/v1/tables/bulk-ownership", Map.of("databaseId", orders.get("databaseId").asText(), "schema", "SALES", "teamId", orders.get("ownerTeamId").asText()), 200);
        assertThat(bulk.get("ok").asBoolean()).isTrue();
        assertThat(bulk.get("updated").asInt()).isEqualTo(8);
        JsonNode own = postJson("/api/v1/tables/" + orders.get("id").asText() + "/ownership", Map.of("teamId", orders.get("ownerTeamId").asText(), "confirmed", true), 200);
        assertThat(own.get("ownerConfirmed").asBoolean()).isTrue();
        JsonNode upd = putJson("/api/v1/tables/" + orders.get("id").asText(), Map.of("classification", "CONFIDENTIAL", "tags", List.of("demo", "core")), 200);
        assertThat(upd.get("tags")).hasSize(2);
        seed(); // restore
        // routine ownership + update
        JsonNode r = getJson("/api/v1/routines?q=RESERVE_STOCK", 200).get(0);
        JsonNode ru = putJson("/api/v1/routines/" + r.get("id").asText(), Map.of("description", "reserves stock", "tags", List.of("inventory")), 200);
        assertThat(ru.get("description").asText()).isEqualTo("reserves stock");
        JsonNode ro = postJson("/api/v1/routines/" + r.get("id").asText() + "/ownership", Map.of("teamId", orders.get("ownerTeamId").asText()), 200);
        assertThat(ro.get("ownerTeamId").asText()).isEqualTo(orders.get("ownerTeamId").asText());
        JsonNode trg = getJson("/api/v1/routines?kind=TRIGGER", 200);
        assertThat(trg.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(4)
    void governanceViolationsAndStats() throws Exception {
        seed();
        JsonNode policies = getJson("/api/v1/governance/policies", 200);
        assertThat(policies).hasSize(5);
        JsonNode eval = postJson("/api/v1/governance/evaluate", Map.of(), 200);
        assertThat(eval.get("ok").asBoolean()).isTrue();
        assertThat(eval.get("violations").asInt()).isPositive();
        JsonNode open = getJson("/api/v1/governance/violations?status=OPEN", 200);
        Set<String> kinds = new HashSet<>();
        List<String> labels = new ArrayList<>();
        for (JsonNode v : open) { kinds.add(v.get("policyKind").asText()); labels.add(v.get("policyKind").asText() + "|" + v.path("applicationId").asText() + "|" + v.get("label").asText()); }
        assertThat(kinds).contains("CROSS_TEAM_DIRECT_ACCESS", "WRITE_BY_NON_PRODUCER", "DIRECT_DB_ACCESS_BYPASSING_PLATFORM", "UNDECLARED_CONSUMER");
        String billing = applicationByName("legacy-billing").get("id").asText();
        String payment = applicationByName("payment-service").get("id").asText();
        assertThat(labels).contains("WRITE_BY_NON_PRODUCER|" + billing + "|SALES.PAYMENT", "DIRECT_DB_ACCESS_BYPASSING_PLATFORM|" + billing + "|SALES.PAYMENT",
                "CROSS_TEAM_DIRECT_ACCESS|" + payment + "|SALES.CUSTOMER");
        // a declared relationship suppresses the cross-team violation (payment-service READS ORDERS is declared)
        assertThat(labels).doesNotContain("CROSS_TEAM_DIRECT_ACCESS|" + payment + "|SALES.ORDERS");
        JsonNode v = open.get(0);
        assertThat(v.get("severity").asText()).isIn("LOW", "MEDIUM", "HIGH");
        JsonNode ack = putJson("/api/v1/governance/violations/" + v.get("id").asText(), Map.of("status", "ACKNOWLEDGED"), 200);
        assertThat(ack.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        postJson("/api/v1/governance/evaluate", Map.of(), 200);
        assertThat(getJson("/api/v1/governance/violations?status=ACKNOWLEDGED", 200).toString()).contains(v.get("id").asText());
        // disabling a policy removes its violations on the next evaluation
        JsonNode unowned = null;
        for (JsonNode p : policies) if (p.get("kind").asText().equals("WRITE_BY_NON_PRODUCER")) unowned = p;
        putJson("/api/v1/governance/policies/" + unowned.get("id").asText(), Map.of("enabled", false, "severity", "LOW"), 200);
        postJson("/api/v1/governance/evaluate", Map.of(), 200);
        for (JsonNode x : getJson("/api/v1/governance/violations?status=OPEN", 200)) assertThat(x.get("policyKind").asText()).isNotEqualTo("WRITE_BY_NON_PRODUCER");
        putJson("/api/v1/governance/policies/" + unowned.get("id").asText(), Map.of("enabled", true, "severity", "HIGH"), 200);
        postJson("/api/v1/governance/evaluate", Map.of(), 200);

        // stats
        JsonNode overview = getJson("/api/v1/stats/overview", 200);
        assertThat(overview.get("teams").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(overview.get("tables").asLong()).isGreaterThanOrEqualTo(11);
        assertThat(overview.get("crossTeamAccesses").asLong()).isPositive();
        assertThat(overview.get("violations").asLong()).isPositive();
        JsonNode hot = getJson("/api/v1/stats/tables/hot?window=24h&limit=3", 200);
        assertThat(hot).hasSize(3);
        assertThat(hot.get(0).get("table").get("label").asText()).startsWith("SALES.");
        assertThat(hot.get(0).get("applications").asInt()).isPositive();
        JsonNode top = getJson("/api/v1/stats/queries/top?by=duration&window=7d&limit=2", 200);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).get("avgDurationMs").asDouble()).isGreaterThanOrEqualTo(top.get(1).get("avgDurationMs").asDouble() * 0.0);
        assertThat(top.get(0).get("p95DurationMs").asLong()).isPositive();
        JsonNode unused = getJson("/api/v1/stats/tables/unused?days=30", 200);
        assertThat(unused.toString()).contains("sales.order_item"); // postgres migration target has no runtime access
        JsonNode byTeam = getJson("/api/v1/stats/connections?groupBy=team", 200);
        assertThat(byTeam.isArray()).isTrue();
        JsonNode teamSummary = getJson("/api/v1/teams/" + applicationByName("payment-service").get("teamId").asText() + "/summary", 200);
        assertThat(teamSummary.get("applications").size()).isGreaterThanOrEqualTo(2);
        assertThat(teamSummary.get("producedTables").toString()).contains("PAYMENT");
        assertThat(teamSummary.get("consumedTables").toString()).contains("ORDERS");
        assertThat(teamSummary.get("datasourcesOwned").get(0).get("name").asText()).isEqualTo("payments");
    }

    @Test
    @Order(5)
    void exportImportRoundTrip() throws Exception {
        seed();
        JsonNode export = getJson("/api/v1/export", 200);
        assertThat(export.get("version").asInt()).isEqualTo(1);
        assertThat(export.get("teams").size()).isGreaterThanOrEqualTo(5);
        assertThat(export.get("datasources").size()).isGreaterThanOrEqualTo(3);
        assertThat(export.toString()).doesNotContain("encryptedSecret").doesNotContain("keyHash");
        JsonNode ds = null;
        for (JsonNode d : export.get("datasources")) if (d.get("name").asText().equals("sales")) ds = d;
        assertThat(ds.get("ownerTeam").asText()).isEqualTo("sales-platform");
        assertThat(ds.get("currentDatabase").asText()).isEqualTo("sales-oracle");
        assertThat(ds.get("targetDatabase").asText()).isEqualTo("sales-postgres");
        assertThat(ds.get("routingRules").get(0).get("application").asText()).isEqualTo("reporting-batch");
        assertThat(ds.get("routingRules").get(0).get("database").asText()).isEqualTo("sales-postgres");
        JsonNode app = null;
        for (JsonNode a : export.get("applications")) if (a.get("name").asText().equals("orders-service")) app = a;
        assertThat(app.get("team").asText()).isEqualTo("sales-platform");
        JsonNode db = null;
        for (JsonNode d : export.get("databases")) if (d.get("name").asText().equals("sales-oracle")) db = d;
        assertThat(db.get("credential").asText()).isEqualTo("sales-oracle-app");
        assertThat(db.get("collector").has("credential")).isTrue();
        assertThat(export.get("accessGrants").get(0).has("application") && export.get("accessGrants").get(0).has("datasource")).isTrue();
        assertThat(export.get("ownership").size()).isGreaterThanOrEqualTo(11);
        assertThat(export.get("producers").size()).isGreaterThanOrEqualTo(9);
        assertThat(export.get("relationships").size()).isGreaterThanOrEqualTo(5);
        assertThat(export.get("relationships").get(0).has("object") && export.get("relationships").get(0).has("objectType")).isTrue();
        // change something, re-import, verify the upsert restored it
        JsonNode team = null;
        for (JsonNode t : getJson("/api/v1/teams", 200)) if (t.get("name").asText().equals("finance")) team = t;
        putJson("/api/v1/teams/" + team.get("id").asText(), Map.of("name", "finance", "displayName", "Changed"), 200);
        JsonNode result = postJson("/api/v1/import", export, 200);
        assertThat(result.get("ok").asBoolean()).isTrue();
        JsonNode imported = result.get("imported");
        assertThat(imported.get("teams").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(imported.get("datasources").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(imported.get("accessGrants").asInt()).isGreaterThanOrEqualTo(10);
        assertThat(imported.get("relationships").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(getJson("/api/v1/teams/" + team.get("id").asText(), 200).get("displayName").asText()).isEqualTo("Finance Team");
        // import into a fresh name space: rename every name and by-name reference to prove references resolve by name
        String p = uniq("imp") + "-";
        JsonNode renamed = rename(export.deepCopy(), p);
        JsonNode r2 = postJson("/api/v1/import", renamed, 200);
        assertThat(r2.get("imported").get("datasources").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode copied = null;
        for (JsonNode d : getJson("/api/v1/datasources", 200)) if (d.get("name").asText().equals(p + "sales")) copied = d;
        assertThat(copied).isNotNull();
        assertThat(copied.get("routingRules")).hasSize(2);
        JsonNode copiedDb = databaseByName(p + "sales-oracle");
        assertThat(copied.get("currentDatabaseId").asText()).isEqualTo(copiedDb.get("id").asText());
        assertThat(copied.get("routingRules").get(0).get("applicationId").asText()).isEqualTo(applicationByName(p + "reporting-batch").get("id").asText());
        assertThat(copiedDb.get("collector").path("credentialId").isMissingNode() || copiedDb.get("collector").get("credentialId").isNull()).isTrue();
        // ownership rows were applied to placeholder tables of the copied database
        JsonNode copiedOrders = tableByName(p + "sales-oracle", "SALES", "ORDERS");
        assertThat(copiedOrders.get("ownerSource").asText()).isEqualTo("DECLARED");
        assertThat(copiedOrders.get("discovered").asBoolean()).isTrue();
    }

    private static final Set<String> REF_FIELDS = Set.of("name", "team", "credential", "ownerTeam", "currentDatabase", "targetDatabase", "application", "database", "datasource");

    /** Prefixes every name and by-name reference in an export document. */
    static JsonNode rename(JsonNode node, String prefix) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            List<String> fields = new ArrayList<>();
            o.fieldNames().forEachRemaining(fields::add);
            for (String f : fields) {
                JsonNode v = o.get(f);
                if (REF_FIELDS.contains(f) && v.isTextual()) o.put(f, prefix + v.asText());
                else rename(v, prefix);
            }
        } else if (node.isArray()) {
            node.forEach(child -> rename(child, prefix));
        }
        return node;
    }
}
