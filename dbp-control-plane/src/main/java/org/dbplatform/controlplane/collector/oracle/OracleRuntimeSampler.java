package org.dbplatform.controlplane.collector.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.collector.Jdbc;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.collector.Model.RuntimeSample;
import org.dbplatform.controlplane.collector.Model.SessionInfo;
import org.dbplatform.controlplane.collector.Model.SqlInfo;
import org.dbplatform.controlplane.collector.RuntimeSampler;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.stereotype.Component;

/**
 * Oracle runtime sampler: V$SESSION every {@code runtimeIntervalSeconds}; V$SQL / V$SQL_PLAN for SQL_IDs
 * not seen before (bounded per sample). Only V$ views that need no extra licence are used: no
 * V$ACTIVE_SESSION_HISTORY, no DBA_HIST_* (Diagnostics Pack).
 */
@Component
public class OracleRuntimeSampler implements RuntimeSampler {
    static final String SESSIONS_SQL = "SELECT s.SID, s.SERIAL#, s.USERNAME, s.STATUS, s.PROGRAM, s.MODULE, s.ACTION, s.CLIENT_IDENTIFIER, s.MACHINE, s.OSUSER, s.PORT,"
            + " s.SERVICE_NAME, s.LOGON_TIME, s.SQL_ID, s.PREV_SQL_ID, s.SQL_EXEC_START"
            + " FROM V$SESSION s WHERE s.TYPE = 'USER' AND s.USERNAME IS NOT NULL AND s.SID <> SYS_CONTEXT('USERENV', 'SID')";
    static final String SQL_SQL = "SELECT SQL_ID, SQL_TEXT, SQL_FULLTEXT, EXECUTIONS, ELAPSED_TIME, ROWS_PROCESSED, MODULE, PARSING_SCHEMA_NAME, LAST_ACTIVE_TIME FROM V$SQL WHERE SQL_ID IN ";
    static final String PLAN_SQL = "SELECT SQL_ID, OBJECT_OWNER, OBJECT_NAME, OBJECT_TYPE, OPERATION FROM V$SQL_PLAN WHERE OBJECT_NAME IS NOT NULL AND SQL_ID IN ";

    @Override public Enums.Engine engine() { return Enums.Engine.ORACLE; }

    @Override
    public RuntimeSample sample(Connection c, DatabaseInstance db, Set<String> knownSqlIds, int maxSql) throws SQLException {
        RuntimeSample out = new RuntimeSample();
        Set<String> newSqlIds = new LinkedHashSet<>();
        Jdbc.forEach(c, SESSIONS_SQL, List.of(), rs -> {
            SessionInfo s = new SessionInfo();
            s.sessionId = rs.getString("SID") + "," + rs.getString("SERIAL#");
            s.dbUser = rs.getString("USERNAME");
            s.status = rs.getString("STATUS");
            s.program = rs.getString("PROGRAM");
            s.module = rs.getString("MODULE");
            s.action = rs.getString("ACTION");
            s.clientIdentifier = rs.getString("CLIENT_IDENTIFIER");
            s.machine = rs.getString("MACHINE");
            s.osUser = rs.getString("OSUSER");
            s.port = Jdbc.integer(rs, "PORT");
            s.serviceName = rs.getString("SERVICE_NAME");
            s.logonTime = Jdbc.instant(rs, "LOGON_TIME");
            s.sqlId = rs.getString("SQL_ID");
            s.prevSqlId = rs.getString("PREV_SQL_ID");
            s.sqlExecStart = Jdbc.instant(rs, "SQL_EXEC_START");
            out.sessions.add(s);
            for (String id : new String[]{s.sqlId, s.prevSqlId}) if (id != null && !knownSqlIds.contains(id) && newSqlIds.size() < maxSql) newSqlIds.add(id);
        });
        if (newSqlIds.isEmpty()) return out;
        List<Object> ids = new ArrayList<>(newSqlIds);
        Map<String, SqlInfo> byId = new LinkedHashMap<>();
        // Oracle limits IN lists to 1000 entries
        for (int from = 0; from < ids.size(); from += 500) {
            List<Object> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            String in = Jdbc.placeholders(chunk.size());
            Jdbc.forEach(c, SQL_SQL + in, chunk, rs -> {
                String id = rs.getString("SQL_ID");
                if (byId.containsKey(id)) return; // one row per SQL_ID (first child)
                SqlInfo s = new SqlInfo();
                s.sqlId = id;
                String full = Jdbc.string(rs, "SQL_FULLTEXT");
                s.sqlText = full != null && !full.isBlank() ? full : rs.getString("SQL_TEXT");
                Long ex = Jdbc.longValue(rs, "EXECUTIONS"); s.executions = ex == null ? 0 : ex;
                Long el = Jdbc.longValue(rs, "ELAPSED_TIME"); s.elapsedMicros = el == null ? 0 : el;
                Long rows = Jdbc.longValue(rs, "ROWS_PROCESSED"); s.rowsProcessed = rows == null ? 0 : rows;
                s.module = rs.getString("MODULE");
                s.parsingSchema = rs.getString("PARSING_SCHEMA_NAME");
                s.lastActive = Jdbc.instant(rs, "LAST_ACTIVE_TIME");
                byId.put(id, s);
            });
            try {
                Jdbc.forEach(c, PLAN_SQL + in, chunk, rs -> {
                    SqlInfo s = byId.get(rs.getString("SQL_ID"));
                    if (s == null) return;
                    String type = rs.getString("OBJECT_TYPE"), op = rs.getString("OPERATION");
                    if (type == null || op == null) return;
                    String t = type.toUpperCase();
                    if (!(t.startsWith("TABLE") || t.startsWith("VIEW") || t.startsWith("MAT_VIEW"))) return;
                    String o = op.toUpperCase();
                    boolean write = o.startsWith("LOAD TABLE") || o.startsWith("LOAD AS SELECT") || o.equals("UPDATE") || o.equals("DELETE") || o.equals("MERGE") || o.startsWith("INSERT");
                    Model.TableTouch touch = new Model.TableTouch(rs.getString("OBJECT_OWNER"), rs.getString("OBJECT_NAME"), write);
                    if (!s.tables.contains(touch)) s.tables.add(touch);
                });
            } catch (SQLException e) {
                out.warnings.add("V$SQL_PLAN not readable (" + e.getMessage() + "): falling back to SQL text analysis");
            }
        }
        // a statement with a write operation on T also reads the same T in the plan: keep the WRITE only
        for (SqlInfo s : byId.values()) {
            List<Model.TableTouch> compact = new ArrayList<>();
            for (Model.TableTouch t : s.tables) {
                boolean writtenElsewhere = s.tables.stream().anyMatch(x -> x.write() && x.name().equalsIgnoreCase(t.name()) && eq(x.schema(), t.schema()));
                if (t.write() || !writtenElsewhere) if (!compact.contains(t)) compact.add(t);
            }
            s.tables.clear();
            s.tables.addAll(compact);
            out.statements.add(s);
        }
        // sessions whose SQL_ID was not found in V$SQL (aged out): still return them so the caller caches "unknown"
        for (String id : newSqlIds) if (!byId.containsKey(id)) { SqlInfo s = new SqlInfo(); s.sqlId = id; out.statements.add(s); }
        return out;
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
}
