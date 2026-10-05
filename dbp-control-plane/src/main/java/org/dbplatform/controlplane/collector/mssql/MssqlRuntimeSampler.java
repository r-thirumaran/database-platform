package org.dbplatform.controlplane.collector.mssql;

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

/** SQL Server runtime sampler: sys.dm_exec_sessions + sys.dm_exec_connections + sys.dm_exec_requests + sys.dm_exec_sql_text. */
@Component
public class MssqlRuntimeSampler implements RuntimeSampler {
    static final String SQL = "SELECT s.session_id, s.login_name, s.host_name, s.program_name, s.status, s.login_time, c.client_net_address, c.client_tcp_port,"
            + " r.status AS request_status, r.start_time, t.text AS sql_text, r.sql_handle"
            + " FROM sys.dm_exec_sessions s LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id"
            + " LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t"
            + " WHERE s.is_user_process = 1 AND s.session_id <> @@SPID";

    @Override public Enums.Engine engine() { return Enums.Engine.MSSQL; }

    @Override
    public RuntimeSample sample(Connection c, DatabaseInstance db, Set<String> knownSqlIds, int maxSql) throws SQLException {
        RuntimeSample out = new RuntimeSample();
        Jdbc.forEach(c, SQL, List.of(), rs -> {
            SessionInfo s = new SessionInfo();
            s.sessionId = rs.getString("session_id");
            s.dbUser = rs.getString("login_name");
            s.machine = rs.getString("host_name");
            s.program = rs.getString("program_name");
            s.status = rs.getString("request_status") != null ? rs.getString("request_status") : rs.getString("status");
            s.logonTime = Jdbc.instant(rs, "login_time");
            s.clientAddr = rs.getString("client_net_address");
            s.port = Jdbc.integer(rs, "client_tcp_port");
            s.sqlText = rs.getString("sql_text");
            s.sqlExecStart = Jdbc.instant(rs, "start_time");
            Object handle = rs.getObject("sql_handle");
            if (handle instanceof byte[] b) s.sqlId = java.util.HexFormat.of().formatHex(b);
            else if (s.sqlText != null) s.sqlId = Integer.toHexString(s.sqlText.hashCode());
            out.sessions.add(s);
        });
        return out;
    }
}
