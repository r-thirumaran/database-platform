package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;

/** Samples the live sessions of one engine and fetches text/plan for statements not yet known. */
public interface RuntimeSampler {
    Enums.Engine engine();
    /** @param knownSqlIds statement ids already analysed (their tables are cached by the caller) */
    Model.RuntimeSample sample(Connection c, DatabaseInstance db, Set<String> knownSqlIds, int maxSql) throws SQLException;
}
