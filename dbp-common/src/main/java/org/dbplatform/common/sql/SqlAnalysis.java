package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;

import java.util.List;

/**
 * Result of {@link SqlAnalyzer#analyze(String, org.dbplatform.common.telemetry.Engine)}.
 *
 * @param operation     coarse statement class
 * @param tables        tables touched, as written in the SQL (deduplicated; WRITE wins over READ)
 * @param routines      procedures / functions referenced (best effort)
 * @param columns       {@code TABLE.COL} references for simple statements (best effort, may be empty)
 * @param normalizedSql literals replaced by {@code ?}, whitespace collapsed, max 4000 chars
 * @param sqlHash       lower-case hex SHA-256 of {@code normalizedSql}
 * @param parseOk       true when JSqlParser understood the statement; false when the regex fallback was used
 */
public record SqlAnalysis(
        SqlOperation operation,
        List<TableAccess> tables,
        List<RoutineRef> routines,
        List<String> columns,
        String normalizedSql,
        String sqlHash,
        boolean parseOk) {

    public SqlAnalysis {
        operation = operation == null ? SqlOperation.OTHER : operation;
        tables = tables == null ? List.of() : List.copyOf(tables);
        routines = routines == null ? List.of() : List.copyOf(routines);
        columns = columns == null ? List.of() : List.copyOf(columns);
        normalizedSql = normalizedSql == null ? "" : normalizedSql;
        sqlHash = sqlHash == null ? "" : sqlHash;
    }

    /** True when at least one table is written (INSERT/UPDATE/DELETE/MERGE target, DDL target). */
    public boolean writes() {
        return tables.stream().anyMatch(t -> t.access() == org.dbplatform.common.telemetry.AccessType.WRITE);
    }

    public List<TableAccess> writtenTables() {
        return tables.stream().filter(t -> t.access() == org.dbplatform.common.telemetry.AccessType.WRITE).toList();
    }

    public List<TableAccess> readTables() {
        return tables.stream().filter(t -> t.access() == org.dbplatform.common.telemetry.AccessType.READ).toList();
    }
}
