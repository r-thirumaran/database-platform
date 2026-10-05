package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based routine extraction for statements JSqlParser does not understand: JDBC {@code {call …}}
 * escapes, {@code CALL x()}, T-SQL {@code EXEC[UTE] [@rc =] proc}, Oracle anonymous PL/SQL blocks
 * ({@code BEGIN pkg.proc(:1); END;}, {@code DECLARE … BEGIN x := fn(1); END;}). Also converts multi-part
 * names into {@link RoutineRef}s with engine-aware schema/package handling.
 */
final class RoutineExtractor {

    private static final String IDENT = "(?:\"[^\"]+\"|\\[[^\\]]+\\]|`[^`]+`|[A-Za-z_][\\w$#]*)";
    private static final String NAME = "(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,3})";

    private static final Pattern JDBC_CALL = Pattern.compile("\\{\\s*(?:\\?\\s*=\\s*)?call\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile("^\\s*CALL\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern EXEC = Pattern.compile("\\bEXEC(?:UTE)?\\s+(?!IMMEDIATE\\b)(?:@[\\w$#]+\\s*=\\s*)?" + NAME, Pattern.CASE_INSENSITIVE);
    /** A procedure call statement inside a block: at statement start, {@code name;} or {@code name(…);}. */
    private static final Pattern PLSQL_STATEMENT = Pattern.compile("(?:^|;|\\bBEGIN\\b|\\bTHEN\\b|\\bELSE\\b|\\bLOOP\\b|\\bDECLARE\\b)\\s*" + NAME + "\\s*(?=\\(|;)", Pattern.CASE_INSENSITIVE);
    /**
     * Function calls anywhere in a block: {@code := fn(}, {@code = fn(}, {@code (fn(}, {@code , fn(}, {@code SELECT fn(}.
     * Group 1 is the word before the name (when separated by whitespace), used to skip table references such as
     * {@code INTO audit_log (msg)}; group 2 is the name.
     */
    private static final Pattern PLSQL_FUNCTION = Pattern.compile("(?:\\b([A-Za-z_][\\w$#]*)\\s+|[:=(,+\\-*/<>|]\\s*|^\\s*)" + NAME + "\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_A_CALL_AFTER = Set.of("INTO", "FROM", "JOIN", "UPDATE", "TABLE", "USING", "DELETE", "MERGE", "LOCK", "EXISTS", "IN", "ON", "AS", "OVER", "PARTITION", "WITHIN", "KEEP", "FILTER", "VALUES");

    private static final Set<String> PLSQL_KEYWORDS = Set.copyOf(List.of(
            "IF", "ELSIF", "ELSE", "END", "LOOP", "WHILE", "FOR", "RETURN", "RAISE", "NULL", "EXIT", "CONTINUE",
            "GOTO", "OPEN", "CLOSE", "FETCH", "COMMIT", "ROLLBACK", "SAVEPOINT", "EXCEPTION", "WHEN", "THEN",
            "BEGIN", "DECLARE", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "FROM", "WHERE", "SET",
            "VALUES", "CASE", "IS", "AS", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "CURSOR", "TYPE",
            "PRAGMA", "FORALL", "BULK", "COLLECT", "EXECUTE", "IMMEDIATE", "USING", "RETURNING", "LOCK", "TABLE",
            "PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "CREATE", "REPLACE", "ALTER", "DROP", "GRANT", "REVOKE",
            "NUMBER", "VARCHAR2", "VARCHAR", "INTEGER", "INT", "DATE", "TIMESTAMP", "BOOLEAN", "CHAR", "CLOB", "BLOB",
            "PLS_INTEGER", "BINARY_INTEGER", "ROWTYPE", "RECORD", "CONSTANT", "DEFAULT", "EXCEPTION_INIT",
            "SQLERRM", "SQLCODE", "OTHERS", "NO_DATA_FOUND", "TOO_MANY_ROWS", "DUP_VAL_ON_INDEX", "ZERO_DIVIDE",
            "RAISE_APPLICATION_ERROR", "SYSDATE", "SYSTIMESTAMP", "TRUE", "FALSE", "DISTINCT", "ORDER", "BY", "GROUP",
            "HAVING", "UNION", "ALL", "ROWNUM", "ROWID", "LEVEL", "PRIOR", "CONNECT", "START", "WITH", "LIMIT", "REVERSE",
            "GO", "PRINT", "THROW", "TRY", "CATCH", "TRAN", "TRANSACTION", "OUTPUT", "NOCOUNT", "ON", "OFF"));

    private RoutineExtractor() {}

    static List<RoutineRef> extract(String scrubbedSql, SqlOperation op, Engine engine) {
        if (scrubbedSql == null || scrubbedSql.isBlank()) {
            return List.of();
        }
        Map<String, RoutineRef> out = new LinkedHashMap<>();
        String sql = scrubbedSql.trim();

        Matcher m = JDBC_CALL.matcher(sql);
        if (m.find()) {
            put(out, m.group(1), engine);
            return new ArrayList<>(out.values());
        }
        m = CALL.matcher(sql);
        if (m.find()) {
            put(out, m.group(1), engine);
            return new ArrayList<>(out.values());
        }
        m = EXEC.matcher(sql);
        while (m.find()) {
            put(out, m.group(1), engine);
        }
        if (!out.isEmpty()) {
            return new ArrayList<>(out.values());
        }

        if (op == SqlOperation.CALL) {
            // anonymous block
            Matcher s = PLSQL_STATEMENT.matcher(sql);
            while (s.find()) {
                String name = s.group(1);
                if (!isKeyword(name)) {
                    put(out, name, engine);
                }
            }
            Matcher f = PLSQL_FUNCTION.matcher(sql);
            while (f.find()) {
                String before = f.group(1);
                String name = f.group(2);
                if (before != null && NOT_A_CALL_AFTER.contains(before.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (!isKeyword(name) && !isBuiltin(name)) {
                    put(out, name, engine);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Builds a routine reference from name parts. Oracle: {@code pkg.proc} stays together as the routine
     * name (schema unknown), {@code schema.pkg.proc} → schema + {@code pkg.proc}. Other engines:
     * {@code schema.proc}; a leading database/server part is dropped.
     */
    static RoutineRef toRoutineRef(List<String> parts, Engine engine) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        int n = parts.size();
        if (n == 1) {
            return new RoutineRef(null, parts.get(0));
        }
        if (engine == Engine.ORACLE || engine == Engine.OTHER || engine == null) {
            if (n == 2) {
                return new RoutineRef(null, parts.get(0) + "." + parts.get(1));
            }
            return new RoutineRef(parts.get(n - 3), parts.get(n - 2) + "." + parts.get(n - 1));
        }
        return new RoutineRef(parts.get(n - 2), parts.get(n - 1));
    }

    static RoutineRef toRoutineRef(String multipartName, Engine engine) {
        return toRoutineRef(Identifiers.splitParts(multipartName), engine);
    }

    static boolean isBuiltin(String multipartName) {
        List<String> parts = Identifiers.splitParts(multipartName);
        return parts.size() == 1 && SqlBuiltins.isBuiltinFunction(parts.get(0));
    }

    private static boolean isKeyword(String multipartName) {
        List<String> parts = Identifiers.splitParts(multipartName);
        return parts.isEmpty() || (parts.size() == 1 && PLSQL_KEYWORDS.contains(parts.get(0).toUpperCase(Locale.ROOT)));
    }

    private static void put(Map<String, RoutineRef> out, String rawName, Engine engine) {
        RoutineRef ref = toRoutineRef(rawName, engine);
        if (ref != null) {
            out.putIfAbsent(ref.qualifiedName().toLowerCase(Locale.ROOT), ref);
        }
    }
}
