package org.dbplatform.controlplane.collector;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies audit-trail rows: CLIENT_PROGRAM_NAME/USERHOST → application, OBJECT → table/routine, ACTION → READS/WRITES/CALLS. */
@Component
public class AuditMerger {
    private final ApplicationRepository applications;
    private final CatalogueService catalogue;
    private final LiveConnectionRegistry live;

    public AuditMerger(ApplicationRepository applications, CatalogueService catalogue, LiveConnectionRegistry live) {
        this.applications = applications; this.catalogue = catalogue; this.live = live;
    }

    @Transactional
    public int apply(DatabaseInstance db, Model.AuditBatch batch) {
        List<Application> apps = applications.findAll();
        SessionAttributor attributor = new SessionAttributor(live);
        int n = 0;
        for (Model.AuditRow row : batch.rows) {
            if (row.objectName() == null) continue;
            Model.SessionInfo s = new Model.SessionInfo();
            s.program = row.program(); s.machine = row.host(); s.osUser = row.osUser(); s.dbUser = row.dbUser();
            Optional<SessionAttributor.Attribution> att = attributor.attribute(db, s, apps);
            if (att.isEmpty()) continue;
            String action = row.action() == null ? "" : row.action().toUpperCase(Locale.ROOT);
            if (action.startsWith("EXECUTE") || action.contains("CALL")) {
                Optional<Routine> r = catalogue.findRoutine(db.getId(), row.objectSchema(), row.objectName(), null);
                if (r.isPresent()) {
                    catalogue.recordRelationship(att.get().applicationId(), ObjectType.ROUTINE, r.get().getId(), RelationshipKind.CALLS, RelationshipSource.COLLECTOR_AUDIT, null, 1, row.at());
                    for (var ex : catalogue.expandRoutineToTables(r.get().getId()).entrySet()) {
                        catalogue.recordRelationship(att.get().applicationId(), ObjectType.TABLE, ex.getKey(), ex.getValue(), RelationshipSource.COLLECTOR_AUDIT, r.get().getId(), 1, row.at());
                    }
                    n++;
                }
                continue;
            }
            RelationshipKind kind = action.startsWith("INSERT") || action.startsWith("UPDATE") || action.startsWith("DELETE") || action.startsWith("MERGE") || action.startsWith("TRUNCATE")
                    ? RelationshipKind.WRITES : action.startsWith("SELECT") ? RelationshipKind.READS : null;
            if (kind == null) continue;
            Optional<DbTable> t = catalogue.findTable(db.getId(), row.objectSchema(), row.objectName(), null);
            if (t.isEmpty()) continue;
            catalogue.recordRelationship(att.get().applicationId(), ObjectType.TABLE, t.get().getId(), kind, RelationshipSource.COLLECTOR_AUDIT, null, 1, row.at());
            n++;
        }
        return n;
    }
}
