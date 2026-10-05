package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Imports the real demo bootstrap document (deploy/bootstrap/platform-config.json) and exports it back. */
class BootstrapImportTest extends AbstractApiTest {

    @Test
    void importsTheBootstrapDocumentAndRoundTrips() throws Exception {
        Path file = Path.of("..", "deploy", "bootstrap", "platform-config.json");
        Assumptions.assumeTrue(Files.exists(file), "bootstrap document not present");
        JsonNode doc = json.readTree(Files.readString(file));
        JsonNode r = postJson("/api/v1/import", doc, 200);
        assertThat(r.get("ok").asBoolean()).isTrue();
        JsonNode imported = r.get("imported");
        assertThat(imported.get("teams").asInt()).isEqualTo(2);
        assertThat(imported.get("applications").asInt()).isEqualTo(3);
        assertThat(imported.get("credentials").asInt()).isEqualTo(4);
        assertThat(imported.get("databases").asInt()).isEqualTo(2);
        assertThat(imported.get("datasources").asInt()).isEqualTo(3);
        assertThat(imported.get("accessGrants").asInt()).isEqualTo(5);
        assertThat(imported.get("ownership").asInt()).isEqualTo(4);
        assertThat(imported.get("producers").asInt()).isEqualTo(3);
        assertThat(imported.get("relationships").asInt()).isEqualTo(4);
        // re-import is idempotent
        assertThat(postJson("/api/v1/import", doc, 200).get("imported").get("teams").asInt()).isEqualTo(2);

        JsonNode oracle = null, pg = null;
        for (JsonNode d : getJson("/api/v1/databases", 200)) {
            if (d.get("name").asText().equals("sales-oracle")) oracle = d;
            if (d.get("name").asText().equals("sales-postgres")) pg = d;
        }
        JsonNode appCred = null, collectorCred = null;
        for (JsonNode c : getJson("/api/v1/credentials", 200)) {
            if (c.get("name").asText().equals("sales-oracle-app")) appCred = c;
            if (c.get("name").asText().equals("sales-oracle-collector")) collectorCred = c;
        }
        assertThat(oracle.get("credentialId").asText()).isEqualTo(appCred.get("id").asText());
        assertThat(oracle.get("collector").get("credentialId").asText()).isEqualTo(collectorCred.get("id").asText());
        assertThat(oracle.get("collector").get("enabled").asBoolean()).isTrue();
        assertThat(oracle.get("jdbcProperties").get("v$session.program").asText()).isEqualTo("dbp-gateway");
        JsonNode sales = null;
        for (JsonNode d : getJson("/api/v1/datasources", 200)) if (d.get("name").asText().equals("sales")) sales = d;
        assertThat(sales.get("currentDatabaseId").asText()).isEqualTo(oracle.get("id").asText());
        assertThat(sales.get("targetDatabaseId").asText()).isEqualTo(pg.get("id").asText());
        assertThat(sales.get("routingRules")).hasSize(1);
        assertThat(sales.get("routingRules").get(0).get("enabled").asBoolean()).isFalse();
        assertThat(sales.get("routingRules").get(0).get("databaseId").asText()).isEqualTo(pg.get("id").asText());
        assertThat(sales.get("poolPolicy").get("maxConnections").asInt()).isEqualTo(8);
        JsonNode legacy = null;
        for (JsonNode a : getJson("/api/v1/applications", 200)) if (a.get("name").asText().equals("legacy-reporting")) legacy = a;
        assertThat(legacy.get("kind").asText()).isEqualTo("LEGACY");
        assertThat(getJson("/api/v1/access-grants?applicationId=" + legacy.get("id").asText(), 200)).hasSize(2);
        // schema-level ownership + table-level override + producers + declared relationships
        JsonNode payment = null, orders = null;
        for (JsonNode t : getJson("/api/v1/tables?databaseId=" + oracle.get("id").asText() + "&schema=SALES", 200)) {
            if (t.get("name").asText().equals("PAYMENT")) payment = t;
            if (t.get("name").asText().equals("ORDERS")) orders = t;
        }
        JsonNode finance = null, salesTeam = null;
        for (JsonNode t : getJson("/api/v1/teams", 200)) {
            if (t.get("name").asText().equals("finance-analytics")) finance = t;
            if (t.get("name").asText().equals("sales-platform")) salesTeam = t;
        }
        assertThat(payment.get("ownerTeamId").asText()).isEqualTo(finance.get("id").asText());
        assertThat(orders.get("ownerTeamId").asText()).isEqualTo(salesTeam.get("id").asText());
        JsonNode ordersService = null;
        for (JsonNode a : getJson("/api/v1/applications", 200)) if (a.get("name").asText().equals("orders-service")) ordersService = a;
        assertThat(orders.get("producerApplicationId").asText()).isEqualTo(ordersService.get("id").asText());
        JsonNode rels = getJson("/api/v1/relationships?applicationId=" + legacy.get("id").asText() + "&source=DECLARED", 200);
        assertThat(rels).hasSize(3);
        JsonNode calls = getJson("/api/v1/relationships?applicationId=" + ordersService.get("id").asText() + "&kind=CALLS&source=DECLARED", 200);
        assertThat(calls).hasSize(1);
        assertThat(getJson("/api/v1/routines?databaseId=" + oracle.get("id").asText() + "&q=PLACE_ORDER", 200)).hasSize(1);
        // a table that appears later in a schema with a schema-level rule inherits the owner (here through a producer declaration)
        String newTable = "new_table_" + uniq("x").replace('-', '_');
        JsonNode disc = postJson("/api/v1/import", json.readTree("{\"producers\":[{\"database\":\"sales-postgres\",\"schema\":\"sales\",\"table\":\"" + newTable + "\",\"application\":\"orders-service\"}]}"), 200);
        assertThat(disc.get("imported").get("producers").asInt()).isEqualTo(1);
        JsonNode inherited = getJson("/api/v1/tables?databaseId=" + pg.get("id").asText() + "&q=" + newTable, 200);
        assertThat(inherited).hasSize(1);
        assertThat(inherited.get(0).get("ownerTeamId").asText()).isEqualTo(salesTeam.get("id").asText());
        assertThat(inherited.get(0).get("ownerSource").asText()).isEqualTo("DECLARED");
        assertThat(inherited.get(0).get("producerApplicationId").asText()).isEqualTo(ordersService.get("id").asText());
        // export produces the same by-name format and can be re-imported
        JsonNode export = getJson("/api/v1/export", 200);
        boolean schemaRow = false;
        for (JsonNode o : export.get("ownership")) if (o.get("table").isNull() && "SALES".equals(o.get("schema").asText())) schemaRow = o.get("team").asText().equals("sales-platform");
        assertThat(schemaRow).isTrue();
        assertThat(postJson("/api/v1/import", export, 200).get("ok").asBoolean()).isTrue();
    }
}
