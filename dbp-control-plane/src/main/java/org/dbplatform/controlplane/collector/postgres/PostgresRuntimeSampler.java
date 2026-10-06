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
 * correlation on client_port) plus pg_stat_statements when the extension is installed (read through the schema it is
 * installed in, in its own try/catch: a missing or unreadable extension only yields a warning).
 */
@Component
public class PostgresRuntimeSampler implements RuntimeSampler {
    static final String ACTIVITY_SQL = "SELECT pid, datname, usename, application_name, host(client_addr) AS client_addr, client_port, backend_start, state, query, query_start"
            + " FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()";
    /** Schema the pg_stat_statements extension is installed in (none when it is not installed in this database). */
    static final String EXT_SQL = "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_stat_statements'";
    private static final String STATEMENTS_SQL = "SELECT queryid, query, calls, total_exec_time, rows FROM %s.pg_stat_statements ORDER BY calls DESC LIMIT ";

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
        // pg_stat_statements is optional and must never cost the pg_stat_activity sample above: it is looked up in the schema
        // the extension lives in (the session's search_path is not trusted) and failures only become a warning
        try {
            sampleStatements(c, knownSqlIds, maxSql, out);
        } catch (SQLException | RuntimeException e) {
            out.warnings.add("pg_stat_statements not sampled: " + e.getMessage());
        }
        return out;
    }

    private static void sampleStatements(Connection c, Set<String> knownSqlIds, int maxSql, RuntimeSample out) throws SQLException {
        List<String> schemas = Jdbc.query(c, EXT_SQL, List.of(), rs -> rs.getString("nspname"));
        if (schemas.isEmpty() || schemas.get(0) == null) return; // extension not installed in this database
        Jdbc.forEach(c, statementsSql(schemas.get(0), maxSql), List.of(), rs -> {
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

    /** The statements query, qualified with the (quoted) schema of the extension. */
    static String statementsSql(String extensionSchema, int maxSql) {
        return STATEMENTS_SQL.formatted('"' + extensionSchema.replace("\"", "\"\"") + '"') + Math.max(1, maxSql);
    }
}
