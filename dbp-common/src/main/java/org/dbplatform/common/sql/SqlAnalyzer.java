package org.dbplatform.common.sql;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns SQL text into a {@link SqlAnalysis}: operation, tables with READ/WRITE access, routines, columns,
 * normalised text and hash. JSqlParser does the heavy lifting; whatever it cannot parse (Oracle
 * {@code INSERT ALL}, anonymous PL/SQL blocks, JDBC {@code {call}} escapes, flashback queries, T-SQL
 * batches, garbage) goes through a regex fallback and is flagged {@code parseOk=false}. The analyzer never
 * throws. Results are memoised per (engine, SQL text) in an LRU cache (default {@value #DEFAULT_CACHE_SIZE}
 * entries). Instances are thread-safe; share one per component.
 */
public final class SqlAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(SqlAnalyzer.class);

    public static final int DEFAULT_CACHE_SIZE = 10_000;
    /** Statements longer than this are not parsed with JSqlParser (regex fallback only) to bound CPU use. */
    public static final int MAX_PARSE_LENGTH = 100_000;
    public static final int MAX_COLUMNS = 200;

    private static final Pattern NUMERIC_BIND = Pattern.compile("(?<![:\\w$#\"\\]])(:\\d+)");
    private static final Pattern RETURNING_INTO = Pattern.compile("(\\bRETURNING\\b[^;]*?)\\s+INTO\\s+[^;]*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TRAILING_SEMICOLONS = Pattern.compile("[;\\s]+$");

    private static final Set<String> PSEUDO_COLUMNS = Set.copyOf(List.of(
            "ROWNUM", "ROWID", "ROWSCN", "ORA_ROWSCN", "LEVEL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE",
            "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIMESTAMP", "LOCALTIME", "USER", "CURRENT_USER",
            "SESSION_USER", "SYSTEM_USER", "NULL", "TRUE", "FALSE", "UNKNOWN", "DEFAULT", "EXCLUDED", "NEW", "OLD",
            "INSERTED", "DELETED", "CONNECT_BY_ISCYCLE", "CONNECT_BY_ISLEAF", "OBJECT_ID", "OBJECT_VALUE",
            "CURRENT_SCHEMA", "CURRENT_CATALOG", "CURRENT_ROLE", "SQLCODE", "SQLERRM", "ROWCOUNT", "IDENTITY",
            "DUMMY", "TABLESAMPLE", "VERSIONS_STARTTIME", "VERSIONS_ENDTIME", "VERSIONS_OPERATION", "VERSIONS_XID"));

    private final LruCache<String, SqlAnalysis> cache;
    private final boolean complexParsing;

    public SqlAnalyzer() {
        this(DEFAULT_CACHE_SIZE);
    }

    /** @param cacheSize number of (engine, sql) analyses to memoise; 0 disables caching. */
    public SqlAnalyzer(int cacheSize) {
        this(cacheSize, false);
    }

    /**
     * @param complexParsing enable JSqlParser's slower backtracking mode for statements the fast mode
     *                       rejects (more statements parse, at a CPU cost); off by default.
     */
    public SqlAnalyzer(int cacheSize, boolean complexParsing) {
        this.cache = new LruCache<>(cacheSize);
        this.complexParsing = complexParsing;
    }

    /** Analyses a statement for the given engine. Never throws; {@code null}/blank SQL yields an empty OTHER analysis. */
    public SqlAnalysis analyze(String sql, Engine engine) {
        Engine eng = engine == null ? Engine.OTHER : engine;
        if (sql == null || sql.isBlank()) {
            return new SqlAnalysis(SqlOperation.OTHER, List.of(), List.of(), List.of(), "", SqlNormalizer.sha256Hex(""), false);
        }
        String key = eng.ordinal() + "\u0000" + sql;
        SqlAnalysis cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        SqlAnalysis result;
        try {
            result = doAnalyze(sql, eng);
        } catch (Throwable t) {
            // last line of defence: never let analysis break the data path
            LOG.debug("SQL analysis failed, returning minimal result: {}", t.toString());
            result = minimal(sql, SqlOperationClassifier.classify(sql));
        }
        cache.put(key, result);
        return result;
    }

    /** Normalised SQL and hash only (no parsing). */
    public static String hash(String sql) {
        return SqlNormalizer.sha256Hex(SqlNormalizer.normalize(sql));
    }

    public int cacheSize() {
        return cache.size();
    }

    public void clearCache() {
        cache.clear();
    }

    // ------------------------------------------------------------------ pipeline

    private SqlAnalysis doAnalyze(String sql, Engine engine) {
        SqlOperation op = SqlOperationClassifier.classify(sql);
        String normalized = SqlNormalizer.normalize(sql);
        String hash = SqlNormalizer.sha256Hex(normalized);

        if (op == SqlOperation.TXN) {
            return new SqlAnalysis(op, List.of(), List.of(), List.of(), normalized, hash, true);
        }
        String scrubbed = SqlNormalizer.scrub(sql);
        if (isJdbcEscape(sql) || isAnonymousBlock(scrubbed, op)) {
            // JSqlParser cannot parse these; regex is the primary path (not a failure)
            List<RoutineRef> routines = RoutineExtractor.extract(scrubbed, op, engine);
            List<TableAccess> tables = RegexTableExtractor.extract(scrubbed, op, engine);
            return new SqlAnalysis(op, tables, routines, List.of(), normalized, hash, false);
        }

        List<Statement> statements = sql.length() <= MAX_PARSE_LENGTH ? parse(sql, engine, scrubbed) : List.of();
        if (statements.isEmpty()) {
            return fallback(sql, scrubbed, op, engine, normalized, hash);
        }
        try {
            if (statements.size() == 1) {
                return fromStatement(statements.get(0), op, engine, scrubbed, normalized, hash);
            }
            return merge(statements, op, engine, scrubbed, normalized, hash);
        } catch (Throwable t) {
            LOG.debug("statement walk failed ({}), using regex fallback: {}", statements.get(0).getClass().getSimpleName(), t.toString());
            return fallback(sql, scrubbed, op, engine, normalized, hash);
        }
    }

    /** Batches ({@code DECLARE @x INT; SET @x = 1; SELECT …}): analyse each statement and union the results. */
    private SqlAnalysis merge(List<Statement> statements, SqlOperation op, Engine engine,
                              String scrubbed, String normalized, String hash) {
        Map<String, TableAccess> tables = new LinkedHashMap<>();
        Map<String, RoutineRef> routines = new LinkedHashMap<>();
        Set<String> columns = new LinkedHashSet<>();
        boolean ok = true;
        for (Statement st : statements) {
            SqlOperation partOp = SqlOperationClassifier.classify(st.toString());
            SqlAnalysis part = fromStatement(st, partOp, engine, scrubbed, normalized, hash);
            ok &= part.parseOk();
            for (TableAccess t : part.tables()) {
                String key = t.qualifiedName().toLowerCase(Locale.ROOT);
                TableAccess existing = tables.get(key);
                if (existing == null) {
                    tables.put(key, t);
                } else if (t.access() == AccessType.WRITE) {
                    tables.put(key, existing.withAccess(AccessType.WRITE));
                }
            }
            for (RoutineRef r : part.routines()) {
                routines.putIfAbsent(r.qualifiedName().toLowerCase(Locale.ROOT), r);
            }
            columns.addAll(part.columns());
        }
        return new SqlAnalysis(op, new ArrayList<>(tables.values()), new ArrayList<>(routines.values()),
                new ArrayList<>(columns), normalized, hash, ok);
    }

    private SqlAnalysis fallback(String sql, String scrubbed, SqlOperation op, Engine engine, String normalized, String hash) {
        List<TableAccess> tables = RegexTableExtractor.extract(scrubbed, op, engine);
        List<RoutineRef> routines = RoutineExtractor.extract(scrubbed, op, engine);
        return new SqlAnalysis(op, tables, routines, List.of(), normalized, hash, false);
    }

    private static SqlAnalysis minimal(String sql, SqlOperation op) {
        String normalized = SqlNormalizer.normalize(sql);
        return new SqlAnalysis(op, List.of(), List.of(), List.of(), normalized, SqlNormalizer.sha256Hex(normalized), false);
    }

    private List<Statement> parse(String sql, Engine engine, String scrubbed) {
        String prepared = prepare(sql, engine);
        if (prepared.isEmpty()) {
            return List.of();
        }
        boolean brackets = engine == Engine.MSSQL || engine == Engine.H2;
        boolean batch = scrubbed.indexOf(';') >= 0 && scrubbed.indexOf(';') < scrubbed.length() - 1;
        try {
            // The parser is driven directly: CCJSqlParserUtil.parse() adds executor/timeout machinery that
            // costs ~5x the parse itself. Complex (backtracking) parsing is a second attempt, if enabled.
            CCJSqlParser parser = CCJSqlParserUtil.newParser(prepared)
                    .withSquareBracketQuotation(brackets)
                    .withAllowComplexParsing(false)
                    .withBackslashEscapeCharacter(false);
            try {
                return batch ? statementsOf(parser.Statements()) : List.of(parser.Statement());
            } catch (Throwable first) {
                if (!complexParsing) {
                    throw first;
                }
                CCJSqlParser retry = CCJSqlParserUtil.newParser(prepared)
                        .withSquareBracketQuotation(brackets)
                        .withAllowComplexParsing(true)
                        .withBackslashEscapeCharacter(false);
                return batch ? statementsOf(retry.Statements()) : List.of(retry.Statement());
            }
        } catch (Throwable t) { // ParseException, TokenMgrError, StackOverflowError, ...
            LOG.trace("JSqlParser rejected statement: {}", t.toString());
            return List.of();
        }
    }

    private static List<Statement> statementsOf(Statements all) {
        return all == null ? List.of() : all.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Engine-specific rewrites that make statements acceptable to JSqlParser without changing their meaning for us. */
    static String prepare(String sql, Engine engine) {
        String s = TRAILING_SEMICOLONS.matcher(sql).replaceAll("");
        if (s.indexOf(':') >= 0) {
            s = NUMERIC_BIND.matcher(s).replaceAll("?");      // Oracle :1, :2 → ?
        }
        if (engine == Engine.ORACLE || engine == Engine.OTHER) {
            s = RETURNING_INTO.matcher(s).replaceFirst("$1"); // RETURNING id INTO :out → RETURNING id
        }
        return s;
    }

    private SqlAnalysis fromStatement(Statement statement, SqlOperation op, Engine engine,
                                      String scrubbed, String normalized, String hash) {
        JsqlVisitor visitor = new JsqlVisitor();
        Set<String> accepted;
        try {
            accepted = visitor.getTables(statement);
        } catch (UnsupportedOperationException e) {
            // statement type TablesNamesFinder does not support (rare DDL etc.): regex for tables, parse still ok
            List<TableAccess> tables = RegexTableExtractor.extract(scrubbed, op, engine);
            List<RoutineRef> routines = RoutineExtractor.extract(scrubbed, op, engine);
            return new SqlAnalysis(op, tables, routines, List.of(), normalized, hash, true);
        }

        // ---- CTE names (TablesNamesFinder misses WITH items of DML statements)
        Set<String> cte = new HashSet<>(visitor.cteNames);
        for (WithItem<?> w : withItems(statement)) {
            if (w.getAlias() != null && w.getAlias().getName() != null) {
                cte.add(Identifiers.unquote(w.getAlias().getName()).toLowerCase(Locale.ROOT));
            }
        }

        // ---- write targets
        Set<String> writeKeys = new LinkedHashSet<>();
        List<Table> targets = new ArrayList<>();
        if (statement instanceof PlainSelect ps && ps.getIntoTables() != null) {
            targets.addAll(ps.getIntoTables()); // T-SQL SELECT … INTO newtable
        }
        if (statement instanceof Insert ins) {
            targets.add(ins.getTable());
        } else if (statement instanceof Update upd) {
            targets.add(upd.getTable());
        } else if (statement instanceof Delete del) {
            if (del.getTables() != null && !del.getTables().isEmpty()) {
                targets.addAll(del.getTables());
            }
            if (del.getTable() != null) {
                targets.add(del.getTable());
            }
        } else if (statement instanceof Merge mrg) {
            targets.add(mrg.getTable());
        } else if (statement instanceof CreateTable ct) {
            targets.add(ct.getTable());
        } else if (statement instanceof Alter alt) {
            targets.add(alt.getTable());
        } else if (statement instanceof Drop drop) {
            if (drop.getName() != null) {
                targets.add(drop.getName());
            }
        } else if (statement instanceof Truncate tr) {
            targets.add(tr.getTable());
        } else if (statement instanceof CreateIndex ci) {
            targets.add(ci.getTable());
        }

        // alias → table key map (for alias targets and column resolution)
        Map<String, Table> byAlias = new HashMap<>();
        Map<String, Table> byName = new HashMap<>();
        for (Table t : visitor.tables) {
            Alias a = t.getAlias();
            if (a != null && a.getName() != null) {
                byAlias.putIfAbsent(Identifiers.unquote(a.getName()).toLowerCase(Locale.ROOT), t);
            }
        }
        for (Table t : visitor.tables) {
            String n = Identifiers.unquote(t.getName()).toLowerCase(Locale.ROOT);
            if (t.getAlias() != null || t.getSchemaName() != null || !byAlias.containsKey(n)) {
                byName.putIfAbsent(n, t); // a bare alias reference (UPDATE c … FROM Customer c) is not a table
            }
        }
        for (Table t : targets) {
            if (t == null) {
                continue;
            }
            Table real = t;
            if (t.getSchemaName() == null && t.getAlias() == null) {
                Table aliased = byAlias.get(Identifiers.unquote(t.getName()).toLowerCase(Locale.ROOT));
                if (aliased != null) {
                    real = aliased;
                }
            }
            writeKeys.add(tableKey(real));
        }

        // ---- tables
        Map<String, TableAccess> tables = new LinkedHashMap<>();
        Map<String, String> keyByUnqualified = new HashMap<>();
        for (Table t : visitor.tables) {
            if (!accepted.contains(visitor.keyOf(t)) && !isWriteTarget(t, targets)) {
                continue; // CTE / derived-table alias filtered by TablesNamesFinder
            }
            TableAccess ta = toTableAccess(t);
            if (ta == null) {
                continue;
            }
            if (ta.schema() == null && cte.contains(ta.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (ta.schema() == null && byAlias.containsKey(ta.name().toLowerCase(Locale.ROOT))
                    && !byName.containsKey(ta.name().toLowerCase(Locale.ROOT))) {
                continue; // alias used as a table reference (T-SQL UPDATE c … FROM Customer c)
            }
            String key = tableKey(t);
            AccessType access = writeKeys.contains(key) ? AccessType.WRITE : AccessType.READ;
            TableAccess existing = tables.get(key);
            if (existing == null) {
                tables.put(key, ta.withAccess(access));
                keyByUnqualified.putIfAbsent(ta.name().toLowerCase(Locale.ROOT), key);
            } else if (access == AccessType.WRITE && existing.access() == AccessType.READ) {
                tables.put(key, existing.withAccess(AccessType.WRITE));
            }
        }
        for (Table t : targets) { // targets not visited by TablesNamesFinder (e.g. CREATE INDEX)
            if (t == null) {
                continue;
            }
            String key = tableKey(t);
            TableAccess ta = toTableAccess(t);
            if (ta != null && !tables.containsKey(key) && !byAlias.containsKey(ta.name().toLowerCase(Locale.ROOT))) {
                tables.put(key, ta.withAccess(AccessType.WRITE));
            }
        }

        // ---- routines
        Map<String, RoutineRef> routines = new LinkedHashMap<>();
        for (String name : visitor.executed) {
            RoutineRef r = RoutineExtractor.toRoutineRef(name, engine);
            if (r != null) {
                routines.putIfAbsent(r.qualifiedName().toLowerCase(Locale.ROOT), r);
            }
        }
        for (Function f : visitor.functions) {
            List<String> parts = f.getMultipartName() != null && !f.getMultipartName().isEmpty()
                    ? f.getMultipartName().stream().map(Identifiers::unquote).toList()
                    : Identifiers.splitParts(f.getName());
            if (parts.isEmpty()) {
                continue;
            }
            if (parts.size() == 1 && SqlBuiltins.isBuiltinFunction(parts.get(0))) {
                continue;
            }
            if (parts.size() == 1 && op != SqlOperation.SELECT && op != SqlOperation.CALL && parts.get(0).length() <= 3) {
                continue; // very short unqualified names in DML are almost always built-ins we do not list
            }
            RoutineRef r = RoutineExtractor.toRoutineRef(parts, engine);
            if (r != null) {
                routines.putIfAbsent(r.qualifiedName().toLowerCase(Locale.ROOT), r);
            }
        }
        if (routines.isEmpty() && op == SqlOperation.CALL) {
            for (RoutineRef r : RoutineExtractor.extract(scrubbed, op, engine)) {
                routines.putIfAbsent(r.qualifiedName().toLowerCase(Locale.ROOT), r);
            }
        }

        // ---- columns (best effort)
        List<String> columns = columns(statement, visitor, tables, byAlias, byName);

        return new SqlAnalysis(op, new ArrayList<>(tables.values()), new ArrayList<>(routines.values()), columns,
                normalized, hash, true);
    }

    private static boolean isWriteTarget(Table t, List<Table> targets) {
        for (Table x : targets) {
            if (x == t) {
                return true;
            }
        }
        return false;
    }

    private static List<WithItem<?>> withItems(Statement st) {
        List<WithItem<?>> items = null;
        if (st instanceof Select s) {
            items = s.getWithItemsList();
        } else if (st instanceof Insert i) {
            items = i.getWithItemsList();
        } else if (st instanceof Update u) {
            items = u.getWithItemsList();
        } else if (st instanceof Delete d) {
            items = d.getWithItemsList();
        } else if (st instanceof Merge m) {
            items = m.getWithItemsList();
        }
        return items == null ? List.of() : items;
    }

    private List<String> columns(Statement statement, JsqlVisitor visitor, Map<String, TableAccess> tables,
                                 Map<String, Table> byAlias, Map<String, Table> byName) {
        if (tables.isEmpty()) {
            return List.of();
        }
        List<Column> cols = new ArrayList<>(visitor.columns);
        if (statement instanceof Insert ins && ins.getColumns() != null) {
            cols.addAll(ins.getColumns());
        }
        if (statement instanceof Update upd && upd.getUpdateSets() != null) {
            for (UpdateSet set : upd.getUpdateSets()) {
                if (set.getColumns() != null) {
                    cols.addAll(set.getColumns());
                }
            }
        }
        if (cols.isEmpty()) {
            return List.of();
        }
        String single = tables.size() == 1 ? tables.values().iterator().next().name() : null;
        Set<String> out = new LinkedHashSet<>();
        for (Column c : cols) {
            String colName = Identifiers.unquote(c.getColumnName());
            if (colName == null || colName.isEmpty() || colName.equals("*") || PSEUDO_COLUMNS.contains(colName.toUpperCase(Locale.ROOT))) {
                continue;
            }
            String tableName = null;
            Table q = c.getTable();
            if (q != null && q.getName() != null) {
                String qn = Identifiers.unquote(q.getName()).toLowerCase(Locale.ROOT);
                Table resolved = byAlias.get(qn);
                if (resolved == null) {
                    resolved = byName.get(qn);
                }
                if (resolved != null) {
                    TableAccess ta = tables.get(tableKey(resolved));
                    tableName = ta != null ? ta.name() : null;
                }
            } else if (single != null) {
                tableName = single;
            }
            if (tableName != null) {
                out.add(tableName + "." + colName);
                if (out.size() >= MAX_COLUMNS) {
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isJdbcEscape(String sql) {
        int i = SqlOperationClassifier.skipLeadingNoise(sql, 0);
        return i < sql.length() && sql.charAt(i) == '{';
    }

    private static boolean isAnonymousBlock(String scrubbed, SqlOperation op) {
        if (op != SqlOperation.CALL) {
            return false;
        }
        String w = SqlOperationClassifier.wordAt(scrubbed, SqlOperationClassifier.skipLeadingNoise(scrubbed, 0));
        return w.equals("BEGIN") || w.equals("DECLARE");
    }

    private static TableAccess toTableAccess(Table t) {
        if (t == null || t.getName() == null) {
            return null;
        }
        String name = Identifiers.unquote(t.getName());
        String schema = t.getSchemaName() != null ? Identifiers.unquote(t.getSchemaName()) : null;
        if (name.isEmpty()) {
            return null;
        }
        if (name.equalsIgnoreCase("dual") && (schema == null || schema.equalsIgnoreCase("sys"))) {
            return null;
        }
        if (name.startsWith("#") || name.startsWith("@")) {
            return null; // T-SQL temp table / table variable: session-local, not a catalogue object
        }
        return new TableAccess(schema, name, AccessType.READ);
    }

    private static String tableKey(Table t) {
        String name = Identifiers.unquote(t.getName()).toLowerCase(Locale.ROOT);
        String schema = t.getSchemaName() != null ? Identifiers.unquote(t.getSchemaName()).toLowerCase(Locale.ROOT) : null;
        return schema == null ? name : schema + "." + name;
    }
}
