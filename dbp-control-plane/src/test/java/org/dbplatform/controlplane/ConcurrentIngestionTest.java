package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.collector.RuntimeMerger;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.service.telemetry.TelemetryIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression test for the lock timeouts seen in the integration run ("Timeout trying to lock table "application"" in the collector thread and in
 * {@code POST /governance/evaluate}): several threads post query-event batches for the same applications and tables while governance
 * evaluations, a collector merge and graph / summary reads run. Nothing may fail, no event may get lost, and no statement may rewrite a row
 * nobody changed (an {@code update application} here means a transaction is holding row locks it has no business holding). H2 with its default
 * 2 s lock timeout, as in the other tests.
 */
class ConcurrentIngestionTest extends AbstractApiTest {
    static final int WRITERS = 4;
    static final int BATCHES_PER_WRITER = 3;
    /** More than two chunks of {@link TelemetryIngestService#CHUNK_SIZE}: exercises the chunked commits, as the gateway's 500-event batches do. */
    static final int BATCH_SIZE = 450;
    static final int STATEMENTS_PER_WRITER = 5;

    @Autowired DatabaseRepository databases;
    @Autowired RuntimeMerger runtimeMerger;

    @Test
    void concurrentIngestionGovernanceCollectorAndReadsNeitherFailNorRewriteEntities() throws Exception {
        String prefix = uniq("ci");
        String teamId = team(uniq("team")).get("id").asText();
        List<String> appIds = new ArrayList<>(), appNames = new ArrayList<>();
        for (int a = 0; a < 3; a++) {
            String name = prefix + "-app" + a;
            JsonNode app = application(name, teamId, "SERVICE", Map.of("tags", List.of("ci"), "identityRules", Map.of("programNames", List.of(name + "*"), "cidrs", List.of("10.9." + a + ".0/24"))));
            appIds.add(app.get("id").asText());
            appNames.add(name);
        }
        String dbId = database(uniq("ora"), "ORACLE", "oracle-ci", 1521, "FREEPDB1", null, List.of("SALES")).get("id").asText();
        String dsName = uniq("sales");
        String dsId = datasource(dsName, teamId, dbId, null, null).get("id").asText();
        for (String appId : appIds) grant(appId, dsId, Map.of());
        DatabaseInstance db = databases.findById(dbId).orElseThrow();
        Instant ts = Instant.now().minusSeconds(300);

        // Seed serially: creates the catalogue tables and the relationship rows (the first-insert race of two transactions for a brand new
        // table / relationship is a separate matter), so the concurrent phase only updates rows that exist, like a platform in steady state.
        for (int a = 0; a < 3; a++) {
            assertThat(post(List.of(event(prefix + "-seed-" + a, ts, appIds.get(a), appNames.get(a), dsName, dbId, "seed-" + a)))).isEqualTo(1);
        }
        runtimeMerger.apply(db, sample(appNames), new RuntimeMerger.State());
        postJson("/api/v1/governance/evaluate", Map.of(), 200);
        JsonNode tables = getJson("/api/v1/tables?databaseId=" + dbId, 200);
        assertThat(tables).hasSize(2);
        String tableId = tables.get(0).get("id").asText();

        AtomicBoolean writersDone = new AtomicBoolean();
        AtomicInteger evaluations = new AtomicInteger(), reads = new AtomicInteger(), merges = new AtomicInteger();
        int expectedEvents = WRITERS * BATCHES_PER_WRITER * BATCH_SIZE;
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 3);
        try (SqlRecorder.Capture c = SqlRecorder.start()) {
            List<Future<Integer>> writers = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                final int writer = w;
                writers.add(pool.submit(() -> {
                    int accepted = 0;
                    List<QueryEvent> first = null;
                    for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                        // applications in blocks (app0 events, then app1, then app2): every chunk takes its row locks in the same order in
                        // every thread, so the test measures lock *hold times*, not deadlocks between arbitrarily ordered events
                        List<QueryEvent> batch = new ArrayList<>();
                        for (int i = 0; i < BATCH_SIZE; i++) {
                            int a = i * 3 / BATCH_SIZE;
                            batch.add(event(prefix + "-w" + writer + "-b" + b + "-" + i, ts, appIds.get(a), appNames.get(a), dsName, dbId, prefix + "-h" + writer + "-" + (i % STATEMENTS_PER_WRITER)));
                        }
                        if (first == null) first = batch;
                        accepted += post(batch);
                    }
                    assertThat(post(first)).as("replaying a batch is idempotent on eventId").isZero();
                    return accepted;
                }));
            }
            Future<?> governance = pool.submit(() -> {
                do {
                    postJson("/api/v1/governance/evaluate", Map.of(), 200);
                    evaluations.incrementAndGet();
                } while (!writersDone.get());
                return null;
            });
            Future<?> reader = pool.submit(() -> {
                do {
                    getJson("/api/v1/graph?root=application:" + appIds.get(0) + "&depth=2", 200);
                    getJson("/api/v1/applications/" + appIds.get(1) + "/summary", 200);
                    getJson("/api/v1/tables/" + tableId + "/summary", 200);
                    getJson("/api/v1/stats/overview", 200);
                    getJson("/api/v1/applications", 200);
                    reads.incrementAndGet();
                } while (!writersDone.get());
                return null;
            });
            Future<?> collector = pool.submit(() -> {
                do {
                    runtimeMerger.apply(db, sample(appNames), new RuntimeMerger.State());
                    merges.incrementAndGet();
                } while (!writersDone.get());
                return null;
            });

            int accepted = 0;
            Throwable failure = null;
            for (Future<Integer> f : writers) {
                try {
                    accepted += f.get(180, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failure = failure == null ? e : failure;
                }
            }
            writersDone.set(true);
            for (Future<?> f : List.of(governance, reader, collector)) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failure = failure == null ? e : failure;
                }
            }
            if (failure != null) throw new AssertionError("concurrent run failed: " + failure, failure);

            assertThat(accepted).as("every event of every batch accepted exactly once").isEqualTo(expectedEvents);
            assertThat(evaluations.get()).isGreaterThanOrEqualTo(1);
            assertThat(reads.get()).isGreaterThanOrEqualTo(1);
            assertThat(merges.get()).isGreaterThanOrEqualTo(1);
            // sanity: the capture saw the work
            assertThat(c.statements().stream().filter(s -> s.startsWith("insert into query_event_raw")).count()).isGreaterThanOrEqualTo(expectedEvents);
            assertThat(c.updatesOf("relationship")).isNotEmpty();
            // the point: nothing rewrote a row it did not change
            c.assertNoUpdates("concurrent ingestion + governance + collector merge + reads", SpuriousUpdateTest.ENTITY_TABLES.toArray(String[]::new));
        } finally {
            pool.shutdownNow();
        }

        // Each writer owns its query_stat rows (hash per writer), so those counters must be exact whatever the interleaving.
        JsonNode top = getJson("/api/v1/stats/queries/top?by=count&window=24h&limit=1000&databaseId=" + dbId, 200);
        for (int w = 0; w < WRITERS; w++) {
            long total = 0;
            for (JsonNode q : top) if (q.get("sqlHash").asText().startsWith(prefix + "-h" + w + "-")) total += q.get("count").asLong();
            assertThat(total).as("query_stat count of writer %d", w).isEqualTo((long) BATCHES_PER_WRITER * BATCH_SIZE);
        }
        assertThat(getJson("/api/v1/relationships?applicationId=" + appIds.get(0) + "&source=GATEWAY", 200)).isNotEmpty();
        assertThat(getJson("/api/v1/relationships?applicationId=" + appIds.get(0) + "&source=COLLECTOR_SESSION", 200)).isNotEmpty();
    }

    private int post(List<QueryEvent> events) throws Exception {
        return internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(events), 202).get("accepted").asInt();
    }

    private static QueryEvent event(String eventId, Instant ts, String appId, String appName, String dsName, String dbId, String hash) {
        return QueryEvent.builder().eventId(eventId).timestamp(ts).gatewayId("gw-ci").sessionId("s-" + hash).applicationId(appId).application(appName)
                .datasource(dsName).databaseId(dbId).engine(Engine.ORACLE).sqlHash(hash)
                .sqlNormalized("UPDATE sales.orders o SET status = ? WHERE o.customer_id IN (SELECT id FROM sales.customer)").operation(SqlOperation.UPDATE)
                .tables(List.of(TableAccess.read("SALES", "CUSTOMER"), TableAccess.write("SALES", "ORDERS"))).durationMs(3).rows(1).build();
    }

    /** One session per application (attributed through its programNames identity rule), each running a statement on both tables. */
    private static Model.RuntimeSample sample(List<String> appNames) {
        Model.RuntimeSample sample = new Model.RuntimeSample();
        Model.SqlInfo sql = new Model.SqlInfo();
        sql.sqlId = "ci-sql"; sql.sqlText = "UPDATE sales.orders SET x = 1 WHERE c IN (SELECT id FROM sales.customer)";
        sql.tables.add(new Model.TableTouch("SALES", "ORDERS", true));
        sql.tables.add(new Model.TableTouch("SALES", "CUSTOMER", false));
        sample.statements.add(sql);
        for (int i = 0; i < appNames.size(); i++) {
            Model.SessionInfo s = new Model.SessionInfo();
            s.sessionId = "ci-" + i; s.dbUser = "SALES_APP"; s.status = "ACTIVE"; s.program = appNames.get(i) + "-jdbc"; s.machine = "ci-host"; s.sqlId = "ci-sql";
            s.logonTime = Instant.now().minusSeconds(60);
            sample.sessions.add(s);
        }
        return sample;
    }
}
