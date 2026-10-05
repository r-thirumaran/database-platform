package org.dbplatform.controlplane.collector.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dbplatform.controlplane.collector.AuditSampler;
import org.dbplatform.controlplane.collector.Jdbc;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.stereotype.Component;

/**
 * Reads UNIFIED_AUDIT_TRAIL incrementally by EVENT_TIMESTAMP (requires AUDIT_VIEWER or SELECT on the
 * view and an audit policy on the crawled schemas, e.g. {@code AUDIT POLICY ... ACTIONS SELECT, INSERT, ...}).
 * Unified auditing is part of the database licence (no extra option needed).
 */
@Component
public class OracleAuditSampler implements AuditSampler {
    static final String SQL = "SELECT EVENT_TIMESTAMP, DBUSERNAME, CLIENT_PROGRAM_NAME, USERHOST, OS_USERNAME, ACTION_NAME, OBJECT_SCHEMA, OBJECT_NAME, SQL_TEXT, SESSIONID"
            + " FROM UNIFIED_AUDIT_TRAIL WHERE EVENT_TIMESTAMP > ? AND OBJECT_SCHEMA IN ";

    @Override public Enums.Engine engine() { return Enums.Engine.ORACLE; }

    @Override
    public Model.AuditBatch sample(Connection c, DatabaseInstance db, List<String> schemas, Instant since, int maxRows) throws SQLException {
        Model.AuditBatch out = new Model.AuditBatch();
        if (schemas.isEmpty()) return out;
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(since));
        for (String s : schemas) params.add(s.toUpperCase(Locale.ROOT));
        String sql = SQL + Jdbc.placeholders(schemas.size()) + " ORDER BY EVENT_TIMESTAMP FETCH FIRST " + Math.max(1, maxRows) + " ROWS ONLY";
        Jdbc.forEach(c, sql, params, rs -> {
            Instant at = Jdbc.instant(rs, "EVENT_TIMESTAMP");
            out.rows.add(new Model.AuditRow(at, rs.getString("DBUSERNAME"), rs.getString("CLIENT_PROGRAM_NAME"), rs.getString("USERHOST"), rs.getString("OS_USERNAME"),
                    rs.getString("ACTION_NAME"), rs.getString("OBJECT_SCHEMA"), rs.getString("OBJECT_NAME"), Jdbc.string(rs, "SQL_TEXT"), rs.getString("SESSIONID")));
            if (at != null && (out.cursor == null || at.isAfter(out.cursor))) out.cursor = at;
        });
        return out;
    }
}
