package org.dbplatform.controlplane.collector.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.dbplatform.controlplane.collector.Jdbc;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.collector.Model.RuntimeSample;
import org.dbplatform.controlplane.collector.Model.SessionInfo;
import org.dbplatform.controlplane.collector.RuntimeSampler;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL runtime sampler: pg_stat_activity (identity via pgApplicationNames / cidrs / proxy
 * correlation on client_port) plus pg_stat_statements when the extension is installed.
 */
@Component
public class PostgresRuntimeSampler implements RuntimeSampler {
    static final String ACTIVITY_SQL = "SELECT pid, datname, usename, application_name, host(client_addr) AS client_addr, client_port, backend_start, state, query, query_start"
            + " FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()";
    static final String EXT_SQL = "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'";
    static final String STATEMENTS_SQL = "SELECT queryid, query, calls, total_exec_time, rows FROM pg_stat_statements ORDER BY calls DESC LIMIT ";

    @Override public Enums.Engine engine() { return Enums.Engine.POSTGRES; }

    @Override
    public RuntimeSample sample(Connection c, DatabaseInstance db, Set<String> knownSqlIds, int maxSql) throws SQLException {
        RuntimeSample out = new RuntimeSample();
        Jdbc.forEach(c, ACTIVITY_SQL, List.of(), rs -> {
            SessionInfo s = new SessionInfo();
            s.sessionId = rs.getString("pid");
            s.dbUser = rs.getString("usename");
            s.program = rs.getString("application_name");
            s.clientAddr = rs.getString("client_addr");
            s.port = Jdbc.integer(rs, "client_port");
            s.logonTime = Jdbc.instant(rs, "backend_start");
            s.status = rs.getString("state");
            s.sqlText = rs.getString("query");
            s.sqlExecStart = Jdbc.instant(rs, "query_start");
            s.serviceName = rs.getString("datname");
            s.machine = s.clientAddr;
            // pg_stat_activity has no statement id: hash the text so repeated samples of one statement count once
            s.sqlId = s.sqlText == null || s.sqlText.isBlank() ? null : Integer.toHexString(s.sqlText.hashCode());
            out.sessions.add(s);
        });
        boolean hasExt = !Jdbc.query(c, EXT_SQL, List.of(), rs -> 1).isEmpty();
        if (hasExt) {
            Jdbc.forEach(c, STATEMENTS_SQL + Math.max(1, maxSql), List.of(), rs -> {
                String id = "pgss:" + rs.getString("queryid");
                if (knownSqlIds.contains(id)) return;
                Model.SqlInfo s = new Model.SqlInfo();
                s.sqlId = id;
                s.sqlText = rs.getString("query");
                Long calls = Jdbc.longValue(rs, "calls"); s.executions = calls == null ? 0 : calls;
                Object t = rs.getObject("total_exec_time"); s.elapsedMicros = t instanceof Number n ? (long) (n.doubleValue() * 1000) : 0;
                Long rows = Jdbc.longValue(rs, "rows"); s.rowsProcessed = rows == null ? 0 : rows;
                out.statements.add(s);
            });
        }
        return out;
    }
}
