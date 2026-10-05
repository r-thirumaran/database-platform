package org.dbplatform.controlplane.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.AbstractApiTest;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Session attribution (identity rules + proxy correlation) and relationship derivation from a runtime sample. */
class RuntimeMergerTest extends AbstractApiTest {
    @Autowired RuntimeMerger merger;
    @Autowired CatalogueMerger catalogueMerger;
    @Autowired LiveConnectionRegistry live;
    @Autowired DatabaseRepository databases;

    @Test
    void attributesSessionsAndDerivesRelationships() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String byProgram = application(uniq("orders"), teamId, "SERVICE", Map.of("identityRules", Map.of("programNames", List.of("JDBC Thin Client/orders*")))).get("id").asText();
        String byMachine = application(uniq("batch"), teamId, "BATCH", Map.of("identityRules", Map.of("machinePatterns", List.of("rpt-*")))).get("id").asText();
        String byProxy = application(uniq("portal"), teamId, "UI", Map.of()).get("id").asText();
        String dbId = database(uniq("ora"), "ORACLE", "oracle-rt", 1521, "FREEPDB1", null, List.of("SALES")).get("id").asText();
        DatabaseInstance db = databases.findById(dbId).orElseThrow();

        // catalogue via the merger (as a dictionary crawl would)
        Model.CrawlResult crawl = new Model.CrawlResult();
        Model.TableInfo orders = new Model.TableInfo("SALES", "ORDERS", org.dbplatform.controlplane.domain.Enums.TableKind.TABLE);
        orders.columns.add(new Model.ColumnInfo("ORDER_ID", 1, "NUMBER", null, 12, 0, false, null, null));
        crawl.tables.add(orders);
        crawl.tables.add(new Model.TableInfo("SALES", "CUSTOMER", org.dbplatform.controlplane.domain.Enums.TableKind.TABLE));
        CatalogueMerger.MergeStats ms = catalogueMerger.apply(db, crawl);
        assertThat(ms.tables()).isEqualTo(2);
        JsonNode tables = getJson("/api/v1/tables?databaseId=" + dbId, 200);
        assertThat(tables).hasSize(2);
        String ordersId = null;
        for (JsonNode t : tables) if (t.get("name").asText().equals("ORDERS")) ordersId = t.get("id").asText();
        assertThat(getJson("/api/v1/tables/" + ordersId + "/columns", 200)).hasSize(1);
        // re-applying the crawl is idempotent
        catalogueMerger.apply(db, crawl);
        assertThat(getJson("/api/v1/tables?databaseId=" + dbId, 200)).hasSize(2);

        // a proxied connection to this database with proxyLocalPort 40321 identified as "portal"
        live.opened(new LiveConnectionRegistry.ProxyConnection("c-1", "proxy-x", "oracle-rt", 1521, 40321, byProxy, "portal", null, null, "10.1.1.1", "JDBC Thin Client", "portal-1", "app", null, Instant.now(), "ORACLE", Instant.now()));

        Model.RuntimeSample sample = new Model.RuntimeSample();
        sample.sessions.add(session("1,1", "JDBC Thin Client/orders", "orders-7f9c", 50000, "s1"));
        sample.sessions.add(session("2,2", "sqlplus", "rpt-01", 50001, "s2"));
        sample.sessions.add(session("3,3", "JDBC Thin Client", "unknown-host", 40321, "s3"));
        sample.sessions.add(session("4,4", "toad.exe", "laptop", 50002, "s1"));
        Model.SqlInfo s1 = new Model.SqlInfo(); s1.sqlId = "s1"; s1.sqlText = "UPDATE sales.orders SET status = :1"; s1.tables.add(new Model.TableTouch("SALES", "ORDERS", true));
        Model.SqlInfo s2 = new Model.SqlInfo(); s2.sqlId = "s2"; s2.sqlText = "SELECT * FROM orders o JOIN customer c ON c.id = o.customer_id"; // no plan → text analysis
        Model.SqlInfo s3 = new Model.SqlInfo(); s3.sqlId = "s3"; s3.sqlText = "SELECT * FROM sales.customer";
        sample.statements.addAll(List.of(s1, s2, s3));
        RuntimeMerger.State state = new RuntimeMerger.State();
        RuntimeMerger.Result r = merger.apply(db, sample, state);
        assertThat(r.sessions()).isEqualTo(4);
        assertThat(r.attributed()).isEqualTo(3);

        JsonNode relsProgram = getJson("/api/v1/relationships?applicationId=" + byProgram, 200);
        assertThat(relsProgram).hasSize(1);
        assertThat(relsProgram.get(0).get("kind").asText()).isEqualTo("WRITES");
        assertThat(relsProgram.get(0).get("source").asText()).isEqualTo("COLLECTOR_SESSION");
        assertThat(relsProgram.get(0).get("confidence").asDouble()).isEqualTo(0.6);
        JsonNode relsMachine = getJson("/api/v1/relationships?applicationId=" + byMachine, 200);
        assertThat(relsMachine).hasSize(2); // orders + customer resolved through the configured schema list
        JsonNode relsProxy = getJson("/api/v1/relationships?applicationId=" + byProxy, 200);
        assertThat(relsProxy).hasSize(1);
        assertThat(relsProxy.get(0).get("source").asText()).isEqualTo("PROXY_CORRELATION");
        assertThat(relsProxy.get(0).get("confidence").asDouble()).isEqualTo(0.9);
        // the same sample again does not double count (session/sql pair already counted)
        merger.apply(db, sample, state);
        assertThat(getJson("/api/v1/relationships?applicationId=" + byProgram, 200).get(0).get("queryCount").asLong()).isEqualTo(1);
        // live snapshot includes unattributed sessions
        JsonNode liveRows = getJson("/api/v1/connections/live", 200);
        int collectorRows = 0;
        for (JsonNode row : liveRows) if ("COLLECTOR".equals(row.get("source").asText()) && db.getName().equals(row.path("database").asText())) collectorRows++;
        assertThat(collectorRows).isEqualTo(4);
        // governance flags the direct (non-platform) access
        postJson("/api/v1/governance/evaluate", Map.of(), 200);
        boolean direct = false;
        for (JsonNode v : getJson("/api/v1/governance/violations?status=OPEN", 200)) {
            if (v.get("policyKind").asText().equals("DIRECT_DB_ACCESS_BYPASSING_PLATFORM") && v.path("applicationId").asText().equals(byProgram)) direct = true;
        }
        assertThat(direct).isTrue();
        live.closed("oracle-rt", 1521, 40321);
    }

    private static Model.SessionInfo session(String id, String program, String machine, int port, String sqlId) {
        Model.SessionInfo s = new Model.SessionInfo();
        s.sessionId = id; s.dbUser = "SALES_APP"; s.status = "ACTIVE"; s.program = program; s.machine = machine; s.port = port; s.sqlId = sqlId;
        s.logonTime = Instant.now().minusSeconds(600);
        return s;
    }
}
