package org.dbplatform.controlplane.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.Chunks;
import org.dbplatform.controlplane.service.telemetry.LiveConnection;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a {@link Model.RuntimeSample}: attributes sessions to applications, derives READS/WRITES
 * relationships (source COLLECTOR_SESSION or PROXY_CORRELATION) and publishes the live connection snapshot.
 * <p>Transactions: the applications (and teams) are read in short read-only transactions and are never part of a write transaction, so
 * a sample can never rewrite or row-lock them; the relationship upserts are committed in chunks of {@link #CHUNK_SIZE} sessions.
 * A chunk that fails leaves the earlier chunks committed; their (session, statement) pairs stay in {@link State#countedKeys}
 * so the retry of the sample does not count them twice.
 */
@Component
public class RuntimeMerger {
    private final ApplicationRepository applications;
    private final TeamRepository teams;
    private final CatalogueService catalogue;
    private final LiveConnectionRegistry live;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    /** Sessions per write transaction. */
    public static final int CHUNK_SIZE = 200;

    public RuntimeMerger(ApplicationRepository applications, TeamRepository teams, CatalogueService catalogue, LiveConnectionRegistry live,
                         PlatformTransactionManager txManager) {
        this.applications = applications; this.teams = teams; this.catalogue = catalogue; this.live = live;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** Per-database memory between samples: statements already analysed and (session, sql) pairs already counted. */
    public static final class State {
        public final Map<String, List<Model.TableTouch>> tablesBySqlId = new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<Model.TableTouch>> e) { return size() > 5000; }
        };
        public Set<String> countedKeys = new HashSet<>();
    }

    public record Result(int sessions, int attributed, int relationships) {}

    /** A session attributed to an application, with the tables its statement touches (empty when nothing is to be recorded). */
    private record Planned(Model.SessionInfo session, Optional<SessionAttributor.Attribution> attribution, Application app, String sqlId, String key,
                           List<Model.TableTouch> touches) {}

    public Result apply(DatabaseInstance db, Model.RuntimeSample sample, State state) {
        Instant now = Instant.now();
        org.dbplatform.common.telemetry.Engine engine = org.dbplatform.common.telemetry.Engine.parse(db.getEngine().name());
        for (Model.SqlInfo s : sample.statements) {
            List<Model.TableTouch> touches = new ArrayList<>(s.tables);
            if (touches.isEmpty() && s.sqlText != null) {
                for (SqlRefs.Ref r : SqlRefs.extract(s.sqlText, engine)) touches.add(new Model.TableTouch(r.schema(), r.name(), r.write()));
            }
            state.tablesBySqlId.put(s.sqlId, touches);
        }
        // read phase: applications (read-only transaction, nothing of it is ever flushed) and the attribution, which is pure computation
        List<Application> apps = readTx.execute(status -> applications.findAll());
        SessionAttributor attributor = new SessionAttributor(live);
        Set<String> newKeys = new HashSet<>();
        List<Planned> planned = new ArrayList<>();
        int attributed = 0;
        for (Model.SessionInfo s : sample.sessions) {
            Optional<SessionAttributor.Attribution> att = attributor.attribute(db, s, apps);
            Application app = att.flatMap(a -> apps.stream().filter(x -> x.getId().equals(a.applicationId())).findFirst()).orElse(null);
            if (app != null) attributed++;
            String sqlId = s.sqlId != null ? s.sqlId : s.prevSqlId;
            String key = null;
            List<Model.TableTouch> touches = List.of();
            if (app != null && sqlId != null) {
                key = s.sessionId + "|" + sqlId;
                newKeys.add(key);
                if (!state.countedKeys.contains(key)) {
                    List<Model.TableTouch> known = state.tablesBySqlId.get(sqlId);
                    if (known == null && s.sqlText != null) {
                        known = new ArrayList<>();
                        for (SqlRefs.Ref r : SqlRefs.extract(s.sqlText, engine)) known.add(new Model.TableTouch(r.schema(), r.name(), r.write()));
                        state.tablesBySqlId.put(sqlId, known);
                    }
                    if (known != null) touches = known;
                }
            }
            planned.add(new Planned(s, att, app, sqlId, key, touches));
        }
        // write phase: relationship upserts, one short transaction per chunk of sessions
        String defaultSchema = db.getCollector().getSchemas().size() == 1 ? db.getCollector().getSchemas().get(0) : null;
        AtomicInteger rels = new AtomicInteger();
        Set<String> committedKeys = new HashSet<>();
        try {
            for (List<Planned> chunk : Chunks.of(planned, CHUNK_SIZE)) {
                if (chunk.stream().anyMatch(p -> !p.touches().isEmpty())) {
                    writeTx.executeWithoutResult(status -> chunk.forEach(p -> recordRelationships(db, p, defaultSchema, now, rels)));
                }
                chunk.stream().map(Planned::key).filter(java.util.Objects::nonNull).forEach(committedKeys::add);
            }
        } catch (RuntimeException | Error e) {
            // keep what was already counted so the retry of this sample does not count it twice
            Set<String> kept = new HashSet<>(state.countedKeys);
            kept.addAll(committedKeys);
            state.countedKeys = kept;
            throw e;
        }
        state.countedKeys = newKeys;
        // live snapshot (teams read in one read-only transaction)
        Map<String, String> teamNames = new java.util.HashMap<>();
        List<LiveConnection> rows = readTx.execute(status -> {
            List<LiveConnection> out = new ArrayList<>();
            for (Planned p : planned) out.add(toRow(db, p.session(), p.app(), p.sqlId(), now, teamNames));
            return out;
        });
        live.setCollectorSnapshot(db.getId(), rows);
        return new Result(sample.sessions.size(), attributed, rels.get());
    }

    private void recordRelationships(DatabaseInstance db, Planned p, String defaultSchema, Instant now, AtomicInteger rels) {
        for (Model.TableTouch t : p.touches()) {
            Optional<DbTable> table = catalogue.findTable(db.getId(), t.schema(), t.name(), p.session().dbUser != null && db.getEngine() == Enums.Engine.ORACLE ? p.session().dbUser : defaultSchema, db.getCollector().getSchemas());
            if (table.isEmpty()) continue; // only catalogued tables: a sampled session never creates placeholders
            catalogue.recordRelationship(p.app().getId(), ObjectType.TABLE, table.get().getId(), t.write() ? RelationshipKind.WRITES : RelationshipKind.READS,
                    p.attribution().get().source(), null, 1, now);
            rels.incrementAndGet();
        }
    }

    private LiveConnection toRow(DatabaseInstance db, Model.SessionInfo s, Application app, String sqlId, Instant now, Map<String, String> teamNames) {
        String team = app == null || app.getTeamId() == null ? null
                : teamNames.computeIfAbsent(app.getTeamId(), id -> teams.findById(id).map(Team::getName).orElse(""));
        if (team != null && team.isEmpty()) team = null;
        Long dur = s.logonTime == null ? null : Duration.between(s.logonTime, now).toSeconds();
        return new LiveConnection("COLLECTOR", app == null ? null : app.getName(), team, null, db.getName(), db.getEngine().name(), s.clientAddr,
                s.program != null ? s.program : s.module, s.machine, s.osUser, s.dbUser, s.status, s.logonTime, dur, sqlId, s.sqlText,
                app == null ? null : app.getId(), db.getId(), null, s.port);
    }
}
