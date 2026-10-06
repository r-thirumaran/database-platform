package org.dbplatform.controlplane.collector;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.Chunks;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies audit-trail rows: CLIENT_PROGRAM_NAME/USERHOST → application, OBJECT → table/routine, ACTION → READS/WRITES/CALLS.
 * <p>The applications are read in a read-only transaction (never part of a write transaction); the relationship upserts are committed in
 * chunks of {@link #CHUNK_SIZE} rows. The audit cursor only advances after the whole batch was applied, so a batch that fails half way is
 * re-read and its already committed rows are counted again (at-least-once; only {@code queryCount} is affected).
 */
@Component
public class AuditMerger {
    private final ApplicationRepository applications;
    private final CatalogueService catalogue;
    private final LiveConnectionRegistry live;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    /** Audit rows per write transaction. */
    public static final int CHUNK_SIZE = 200;

    public AuditMerger(ApplicationRepository applications, CatalogueService catalogue, LiveConnectionRegistry live, PlatformTransactionManager txManager) {
        this.applications = applications; this.catalogue = catalogue; this.live = live;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    public int apply(DatabaseInstance db, Model.AuditBatch batch) {
        List<Application> apps = readTx.execute(status -> applications.findAll());
        SessionAttributor attributor = new SessionAttributor(live);
        AtomicInteger n = new AtomicInteger();
        for (List<Model.AuditRow> chunk : Chunks.of(batch.rows, CHUNK_SIZE)) {
            writeTx.executeWithoutResult(status -> applyChunk(db, chunk, apps, attributor, n));
        }
        return n.get();
    }

    private void applyChunk(DatabaseInstance db, List<Model.AuditRow> rows, List<Application> apps, SessionAttributor attributor, AtomicInteger n) {
        for (Model.AuditRow row : rows) {
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
                    n.incrementAndGet();
                }
                continue;
            }
            RelationshipKind kind = action.startsWith("INSERT") || action.startsWith("UPDATE") || action.startsWith("DELETE") || action.startsWith("MERGE") || action.startsWith("TRUNCATE")
                    ? RelationshipKind.WRITES : action.startsWith("SELECT") ? RelationshipKind.READS : null;
            if (kind == null) continue;
            Optional<DbTable> t = catalogue.findTable(db.getId(), row.objectSchema(), row.objectName(), null);
            if (t.isEmpty()) continue;
            catalogue.recordRelationship(att.get().applicationId(), ObjectType.TABLE, t.get().getId(), kind, RelationshipSource.COLLECTOR_AUDIT, null, 1, row.at());
            n.incrementAndGet();
        }
    }
}
