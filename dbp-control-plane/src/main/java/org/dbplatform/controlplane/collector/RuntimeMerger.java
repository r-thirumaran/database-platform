package org.dbplatform.controlplane.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.dbplatform.controlplane.service.telemetry.LiveConnection;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a {@link Model.RuntimeSample}: attributes sessions to applications, derives READS/WRITES
 * relationships (source COLLECTOR_SESSION or PROXY_CORRELATION) and publishes the live connection snapshot.
 */
@Component
public class RuntimeMerger {
    private final ApplicationRepository applications;
    private final TeamRepository teams;
    private final CatalogueService catalogue;
    private final LiveConnectionRegistry live;

    public RuntimeMerger(ApplicationRepository applications, TeamRepository teams, CatalogueService catalogue, LiveConnectionRegistry live) {
        this.applications = applications; this.teams = teams; this.catalogue = catalogue; this.live = live;
    }

    /** Per-database memory between samples: statements already analysed and (session, sql) pairs already counted. */
    public static final class State {
        public final Map<String, List<Model.TableTouch>> tablesBySqlId = new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<Model.TableTouch>> e) { return size() > 5000; }
        };
        public Set<String> countedKeys = new HashSet<>();
    }

    public record Result(int sessions, int attributed, int relationships) {}

    @Transactional
    public Result apply(DatabaseInstance db, Model.RuntimeSample sample, State state) {
        Instant now = Instant.now();
        for (Model.SqlInfo s : sample.statements) {
            List<Model.TableTouch> touches = new ArrayList<>(s.tables);
            if (touches.isEmpty() && s.sqlText != null) {
                for (SqlRefs.Ref r : SqlRefs.extract(s.sqlText, org.dbplatform.common.telemetry.Engine.parse(db.getEngine().name()))) touches.add(new Model.TableTouch(r.schema(), r.name(), r.write()));
            }
            state.tablesBySqlId.put(s.sqlId, touches);
        }
        List<Application> apps = applications.findAll();
        SessionAttributor attributor = new SessionAttributor(live);
        Set<String> newKeys = new HashSet<>();
        List<LiveConnection> rows = new ArrayList<>();
        int attributed = 0, rels = 0;
        String defaultSchema = db.getCollector().getSchemas().size() == 1 ? db.getCollector().getSchemas().get(0) : null;
        for (Model.SessionInfo s : sample.sessions) {
            Optional<SessionAttributor.Attribution> att = attributor.attribute(db, s, apps);
            Application app = att.flatMap(a -> apps.stream().filter(x -> x.getId().equals(a.applicationId())).findFirst()).orElse(null);
            if (app != null) attributed++;
            String sqlId = s.sqlId != null ? s.sqlId : s.prevSqlId;
            if (app != null && sqlId != null) {
                String key = s.sessionId + "|" + sqlId;
                newKeys.add(key);
                if (!state.countedKeys.contains(key)) {
                    List<Model.TableTouch> touches = state.tablesBySqlId.get(sqlId);
                    if (touches == null && s.sqlText != null) {
                        touches = new ArrayList<>();
                        for (SqlRefs.Ref r : SqlRefs.extract(s.sqlText, org.dbplatform.common.telemetry.Engine.parse(db.getEngine().name()))) touches.add(new Model.TableTouch(r.schema(), r.name(), r.write()));
                        state.tablesBySqlId.put(sqlId, touches);
                    }
                    if (touches != null) {
                        for (Model.TableTouch t : touches) {
                            Optional<DbTable> table = catalogue.findTable(db.getId(), t.schema(), t.name(), s.dbUser != null && db.getEngine() == Enums.Engine.ORACLE ? s.dbUser : defaultSchema, db.getCollector().getSchemas());
                            if (table.isEmpty()) continue; // only catalogued tables: a sampled session never creates placeholders
                            catalogue.recordRelationship(app.getId(), ObjectType.TABLE, table.get().getId(), t.write() ? RelationshipKind.WRITES : RelationshipKind.READS,
                                    att.get().source(), null, 1, now);
                            rels++;
                        }
                    }
                }
            }
            rows.add(toRow(db, s, app, sqlId, now));
        }
        state.countedKeys = newKeys;
        live.setCollectorSnapshot(db.getId(), rows);
        return new Result(sample.sessions.size(), attributed, rels);
    }

    private LiveConnection toRow(DatabaseInstance db, Model.SessionInfo s, Application app, String sqlId, Instant now) {
        String team = app == null || app.getTeamId() == null ? null : teams.findById(app.getTeamId()).map(Team::getName).orElse(null);
        Long dur = s.logonTime == null ? null : Duration.between(s.logonTime, now).toSeconds();
        return new LiveConnection("COLLECTOR", app == null ? null : app.getName(), team, null, db.getName(), db.getEngine().name(), s.clientAddr,
                s.program != null ? s.program : s.module, s.machine, s.osUser, s.dbUser, s.status, s.logonTime, dur, sqlId, s.sqlText,
                app == null ? null : app.getId(), db.getId(), null, s.port);
    }
}
