package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword-driven table extraction used when JSqlParser cannot parse a statement. Works on
 * {@link SqlNormalizer#scrub(String) scrubbed} SQL (comments removed, literals blanked). Recognises
 * {@code FROM a, b c}, {@code JOIN}, {@code INSERT [ALL] INTO}, {@code MERGE INTO}, {@code UPDATE},
 * {@code DELETE [FROM]}, {@code USING}, {@code TABLE} (DDL/LOCK), {@code INDEX … ON}, Oracle {@code (+)}
 * joins, {@code FROM dual}, quoted and bracketed identifiers and T-SQL alias targets
 * ({@code UPDATE c … FROM dbo.Customer c}).
 */
final class RegexTableExtractor {

    private static final String IDENT = "(?:\"[^\"]+\"|\\[[^\\]]+\\]|`[^`]+`|[A-Za-z_][\\w$#]*)";
    private static final String NAME = "(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,3}(?:@[\\w$#.]+)?)";
    private static final String HINT = "(?:/\\*.*?\\*/\\s*)?";

    private static final Pattern FROM = Pattern.compile("\\bFROM\\s+(?:ONLY\\s*\\(?\\s*)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN = Pattern.compile("\\bJOIN\\s+(?:ONLY\\s*\\(?\\s*)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern USING = Pattern.compile("\\bUSING\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern INTO = Pattern.compile("\\bINTO\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile("\\bUPDATE\\s+" + HINT + "(?:(?:LOW_PRIORITY|IGNORE|TOP\\s*\\(\\s*\\d+\\s*\\)|ONLY)\\s*\\(?\\s*)*" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE = Pattern.compile("\\bDELETE\\s+" + HINT + "(?:TOP\\s*\\(\\s*\\d+\\s*\\)\\s*)?(?:FROM\\s+)?(?:ONLY\\s*\\(?\\s*)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile("\\bTABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUNCATE = Pattern.compile("\\bTRUNCATE\\s+(?!TABLE\\b)" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_ON = Pattern.compile("\\bINDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:CONCURRENTLY\\s+)?" + IDENT + "\\s+ON\\s+(?:ONLY\\s+)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS = Pattern.compile("\\G\\s+(?:AS\\s+)?(" + IDENT + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMA_NAME = Pattern.compile("\\G\\s*,\\s*" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE = Pattern.compile("\\bMERGE\\s+" + HINT + "(?:INTO\\s+)?(?:TOP\\s*\\(\\s*\\d+\\s*\\)\\s*)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern CTE_FIRST = Pattern.compile("\\bWITH\\s+(?:RECURSIVE\\s+)?" + NAME + "\\s*(?:\\([^)]*\\))?\\s+AS\\s*(?:NOT\\s+MATERIALIZED\\s+|MATERIALIZED\\s+)?\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern CTE_NEXT = Pattern.compile("\\G.*?\\)\\s*,\\s*" + NAME + "\\s*(?:\\([^)]*\\))?\\s+AS\\s*(?:NOT\\s+MATERIALIZED\\s+|MATERIALIZED\\s+)?\\(", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RETURNING_INTO = Pattern.compile("\\bRETURNING\\b[^;()]*?\\bINTO\\s+[:@]?[\\w$#]+(?:\\s*,\\s*[:@]?[\\w$#]+)*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_INTO_VAR = Pattern.compile("\\bINTO\\s+(?::|@)", Pattern.CASE_INSENSITIVE);

    private RegexTableExtractor() {}

    static List<TableAccess> extract(String scrubbedSql, SqlOperation op, Engine engine) {
        if (scrubbedSql == null || scrubbedSql.isBlank()) {
            return List.of();
        }
        String sql = RETURNING_INTO.matcher(scrubbedSql).replaceAll("");
        Map<String, TableAccess> found = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();

        Set<String> cte = cteNames(sql);

        // sources (READ)
        collect(FROM, sql, AccessType.READ, true, true, found, aliases);
        collect(JOIN, sql, AccessType.READ, false, true, found, aliases);
        collect(USING, sql, AccessType.READ, true, true, found, aliases);

        // targets (WRITE)
        if (op == SqlOperation.INSERT || op == SqlOperation.MERGE || (op == SqlOperation.SELECT && engine == Engine.MSSQL)) {
            // INSERT [ALL] INTO t / WHEN … THEN INTO t / MERGE INTO t / T-SQL SELECT … INTO newtable
            collect(INTO, sql, AccessType.WRITE, false, false, found, aliases);
        }
        if (op == SqlOperation.MERGE) {
            collect(MERGE, sql, AccessType.WRITE, false, false, found, aliases);
        }
        // UPDATE/DELETE targets are collected for every operation: CTEs and blocks may embed them, and the
        // reserved-word guard filters "UPDATE SET" (MERGE / ON CONFLICT), "FOR UPDATE", "ON DELETE CASCADE".
        collect(UPDATE, sql, AccessType.WRITE, false, false, found, aliases);
        collect(DELETE, sql, AccessType.WRITE, false, false, found, aliases);
        if (op == SqlOperation.CALL || op == SqlOperation.OTHER || op == SqlOperation.UPDATE
                || op == SqlOperation.DELETE || op == SqlOperation.SELECT) {
            // INSERT statements embedded in blocks / CTEs: only INSERT INTO counts (SELECT … INTO var does not)
            collect(INTO, stripSelectIntoVariables(sql), AccessType.WRITE, false, false, found, aliases);
        }
        if (op == SqlOperation.DDL || op == SqlOperation.OTHER) {
            collect(TABLE, sql, AccessType.WRITE, false, false, found, aliases);
            collect(TRUNCATE, sql, AccessType.WRITE, false, false, found, aliases);
            collect(INDEX_ON, sql, AccessType.WRITE, false, false, found, aliases);
        }
        for (String name : cte) {
            found.remove(name);
        }

        // resolve alias targets: UPDATE c SET … FROM dbo.Customer c
        List<TableAccess> out = new ArrayList<>(found.size());
        for (TableAccess t : found.values()) {
            if (t.access() == AccessType.WRITE && t.schema() == null) {
                String real = aliases.get(t.name().toLowerCase(Locale.ROOT));
                if (real != null && found.containsKey(real)) {
                    TableAccess target = found.get(real);
                    found.put(real, target.withAccess(AccessType.WRITE));
                    continue;
                }
            }
            out.add(t);
        }
        if (out.size() != found.size()) {
            out = new ArrayList<>(found.size());
            for (TableAccess t : found.values()) {
                if (!(t.schema() == null && aliases.containsKey(t.name().toLowerCase(Locale.ROOT))
                        && found.containsKey(aliases.get(t.name().toLowerCase(Locale.ROOT))))) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** PL/SQL {@code SELECT … INTO l_var} — the INTO targets are variables, not tables; only {@code INSERT INTO} counts. */
    private static String stripSelectIntoVariables(String sql) {
        // keep INTO only when directly preceded by INSERT [ALL|FIRST] or MERGE (or WHEN … THEN of INSERT ALL)
        Matcher m = Pattern.compile("\\b(INSERT(?:\\s+(?:ALL|FIRST))?|MERGE|THEN|ELSE)\\s+INTO\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        List<int[]> keep = new ArrayList<>();
        while (m.find()) {
            keep.add(new int[] {m.start(), m.end()});
        }
        // blank every other INTO
        Matcher into = Pattern.compile("\\bINTO\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        while (into.find()) {
            boolean kept = false;
            for (int[] k : keep) {
                if (into.start() >= k[0] && into.end() <= k[1]) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                sb.append(sql, last, into.start()).append("XXXX");
                last = into.end();
            }
        }
        sb.append(sql.substring(last));
        String s = sb.toString();
        return SELECT_INTO_VAR.matcher(s).replaceAll("XXXX ");
    }

    /** Lower-case keys of CTE names declared by a leading WITH clause. */
    static Set<String> cteNames(String sql) {
        Matcher m = CTE_FIRST.matcher(sql);
        if (!m.find()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        names.add(key(m.group(1)));
        Matcher next = CTE_NEXT.matcher(sql);
        int pos = m.end();
        while (next.find(pos)) {
            names.add(key(next.group(1)));
            pos = next.end();
            next = CTE_NEXT.matcher(sql);
        }
        return names;
    }

    private static String key(String rawName) {
        List<String> parts = Identifiers.splitParts(rawName);
        if (parts.isEmpty()) {
            return "";
        }
        String name = parts.get(parts.size() - 1).toLowerCase(Locale.ROOT);
        return parts.size() >= 2 ? parts.get(parts.size() - 2).toLowerCase(Locale.ROOT) + "." + name : name;
    }

    private static void collect(Pattern p, String sql, AccessType access, boolean commaList, boolean parenIsCall,
                                Map<String, TableAccess> found, Map<String, String> aliases) {
        Matcher m = p.matcher(sql);
        while (m.find()) {
            int end = m.end();
            end = add(sql, m.group(1), end, access, parenIsCall, found, aliases);
            if (commaList) {
                Matcher cm = COMMA_NAME.matcher(sql);
                while (cm.find(end)) {
                    end = add(sql, cm.group(1), cm.end(), access, parenIsCall, found, aliases);
                    cm = COMMA_NAME.matcher(sql);
                }
            }
        }
    }

    /**
     * Adds the name (unless reserved / function call / dual), records an alias if one follows; returns the scan
     * position. {@code parenIsCall}: a {@code (} directly after the name means a table function
     * ({@code TABLE(...)}, {@code unnest(...)}) for sources, but a column list for INSERT targets.
     */
    private static int add(String sql, String rawName, int end, AccessType access, boolean parenIsCall,
                           Map<String, TableAccess> found, Map<String, String> aliases) {
        List<String> parts = Identifiers.splitParts(rawName);
        if (parts.isEmpty()) {
            return end;
        }
        int k = end;
        while (k < sql.length() && Character.isWhitespace(sql.charAt(k))) {
            k++;
        }
        if (parenIsCall && k < sql.length() && sql.charAt(k) == '(') {
            return end;
        }
        String name = parts.get(parts.size() - 1);
        if (parts.size() == 1 && (Identifiers.isReserved(name) || name.equalsIgnoreCase("dual") || name.startsWith("@") || name.startsWith(":"))) {
            return end;
        }
        if (parts.size() == 2 && parts.get(0).equalsIgnoreCase("sys") && name.equalsIgnoreCase("dual")) {
            return end;
        }
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        String key = (schema == null ? "" : schema.toLowerCase(Locale.ROOT) + ".") + name.toLowerCase(Locale.ROOT);
        TableAccess existing = found.get(key);
        if (existing == null) {
            found.put(key, new TableAccess(schema, name, access));
        } else if (access == AccessType.WRITE && existing.access() != AccessType.WRITE) {
            found.put(key, existing.withAccess(AccessType.WRITE));
        }
        // alias
        Matcher am = ALIAS.matcher(sql);
        if (am.find(end)) {
            String alias = Identifiers.unquote(am.group(1));
            if (!Identifiers.isReserved(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), key);
                return am.end();
            }
        }
        return end;
    }
}
