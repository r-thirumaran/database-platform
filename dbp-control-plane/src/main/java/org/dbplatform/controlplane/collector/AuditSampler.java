package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;

/** Optional incremental read of the engine's audit trail. */
public interface AuditSampler {
    Enums.Engine engine();
    Model.AuditBatch sample(Connection c, DatabaseInstance db, List<String> schemas, Instant since, int maxRows) throws SQLException;
}
