package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.collector.RuntimeMerger;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression test for the "update application ... Timeout trying to lock table" defect: entities whose attributes go through
 * a JSON {@code AttributeConverter} (IdentityRules, CollectorConfig, PoolPolicy, TableMigration) were dirty on every flush because the
 * value types had no value-based equals(), so every read-write transaction that merely <em>loaded</em> rows re-wrote them
 * (and held their row locks until the transaction ended). Nothing in this class changes an application, database, datasource,
 * team, table or routine, so no UPDATE of those tables may be issued, whatever the transaction type.
 */
class SpuriousUpdateTest extends AbstractApiTest {
    static final List<String> ENTITY_TABLES = List.of("application", "database_instance", "datasource", "db_table", "routine", "team");

    @Autowired PlatformTransactionManager txManager;
    @Autowired ApplicationRepository applications;
    @Autowired DatabaseRepository databases;
    @Autowired DatasourceRepository datasources;
    @Autowired DbTableRepository tables;
    @Autowired RoutineRepository routines;
    @Autowired TeamRepository teams;
    @Autowired RuntimeMerger runtimeMerger;

    String teamId, appId, appName, dbId, dsName, dsId;

    @BeforeEach
    void fixtures() throws Exception {
        teamId = team(uniq("team")).get("id").asText();
        JsonNode app = application(uniq("orders"), teamId, "SERVICE", Map.of("tags", List.of("a", "b"),
                "identityRules", Map.of("programNames", List.of("JDBC Thin Client/orders*"), "cidrs", List.of("10.0.0.0/8"), "serviceAliases", List.of("orders"))));
        appId = app.get("id").asText();
        appName = app.get("name").asText();
        dbId = database(uniq("ora"), "ORACLE", "oracle-su", 1521, "FREEPDB1", null, List.of("SALES")).get("id").asText();
        dsName = uniq("sales");
        dsId = datasource(dsName, teamId, dbId, null, null).get("id").asText();
        grant(appId, dsId, Map.of());
        // telemetry creates a discovered table + routine and relationships (all committed, entity rows are now "at rest")
        QueryEvent e = QueryEvent.builder().eventId(uniq("evt")).timestamp(Instant.now()).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId)
                .engine(Engine.ORACLE).sqlHash(uniq("h")).sqlNormalized("UPDATE sales.orders SET x = ?").operation(SqlOperation.UPDATE)
                .tables(List.of(TableAccess.write("SALES", "ORDERS"))).routines(List.of(new RoutineRef("SALES", "PLACE_ORDER"))).durationMs(5).rows(1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e)), 202);
        // the first evaluation may legitimately infer the producer of the table; afterwards the data is at rest
        postJson("/api/v1/governance/evaluate", Map.of(), 200);
    }

    private static void assertNoEntityUpdates(SqlRecorder.Capture c, String what) {
        c.assertNoUpdates(what, ENTITY_TABLES.toArray(String[]::new));
    }

    @Test
    void readWriteTransactionThatOnlyLoadsEntitiesWritesNothing() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try (SqlRecorder.Capture c = SqlRecorder.start()) {
            tx.executeWithoutResult(s -> {
                assertThat(applications.findAll()).isNotEmpty();
                assertThat(databases.findAll()).isNotEmpty();
                assertThat(datasources.findAll()).isNotEmpty();
                assertThat(tables.findAll()).isNotEmpty();
                assertThat(routines.findAll()).isNotEmpty();
                assertThat(teams.findAll()).isNotEmpty();
            });
            assertNoEntityUpdates(c, "a read-write transaction that only loads entities");
        }
    }

    @Test
    void readEndpointsWriteNothing() throws Exception {
        try (SqlRecorder.Capture c = SqlRecorder.start()) {
            getJson("/api/v1/applications", 200);
            getJson("/api/v1/applications/" + appId, 200);
            getJson("/api/v1/applications/" + appId + "/summary", 200);
            getJson("/api/v1/databases", 200);
            getJson("/api/v1/datasources", 200);
            getJson("/api/v1/tables", 200);
            getJson("/api/v1/routines", 200);
            getJson("/api/v1/teams", 200);
            getJson("/api/v1/graph?depth=2", 200);
            getJson("/api/v1/stats/overview", 200);
            getJson("/api/v1/governance/violations", 200);
            assertThat(c.updates()).as("GET endpoints must not issue any update (all statements: %s)", c.statements()).isEmpty();
        }
    }

    @Test
    void governanceEvaluateDoesNotRewriteLoadedEntities() throws Exception {
        try (SqlRecorder.Capture c = SqlRecorder.start()) {
            postJson("/api/v1/governance/evaluate", Map.of(), 200);
            assertNoEntityUpdates(c, "POST /governance/evaluate");
        }
    }

    @Test
    void runtimeSampleMergeDoesNotRewriteApplications() {
        DatabaseInstance db = databases.findById(dbId).orElseThrow();
        Model.RuntimeSample sample = new Model.RuntimeSample();
        Model.SessionInfo s = new Model.SessionInfo();
        s.sessionId = "1,1"; s.dbUser = "SALES_APP"; s.status = "ACTIVE"; s.program = "JDBC Thin Client/orders-1"; s.machine = "m1"; s.sqlId = "q1";
        s.logonTime = Instant.now().minusSeconds(60);
        sample.sessions.add(s);
        Model.SqlInfo sql = new Model.SqlInfo();
        sql.sqlId = "q1"; sql.sqlText = "SELECT * FROM sales.orders"; sql.tables.add(new Model.TableTouch("SALES", "ORDERS", false));
        sample.statements.add(sql);
        try (SqlRecorder.Capture c = SqlRecorder.start()) {
            RuntimeMerger.Result r = runtimeMerger.apply(db, sample, new RuntimeMerger.State());
            assertThat(r.attributed()).isEqualTo(1);
            assertThat(r.relationships()).isEqualTo(1);
            assertThat(c.updatesOf("application")).as("RuntimeMerger.apply must not rewrite applications (all statements: %s)", c.statements()).isEmpty();
            assertThat(c.updatesOf("database_instance")).isEmpty();
            assertThat(c.updatesOf("db_table")).as("%s", c.statements()).isEmpty();
        }
    }
}
