package org.dbplatform.controlplane.collector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dbplatform.common.sql.SqlAnalysis;
import org.dbplatform.common.sql.SqlAnalyzer;
import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;

/**
 * Table references in SQL / PL-SQL / T-SQL / plpgsql text. {@link SqlAnalyzer} (JSqlParser based, dbp-common) is
 * authoritative when it understood a DML / query statement ({@code parseOk}); when it did not (procedural bodies,
 * anonymous blocks, vendor syntax) or when the text is a routine / trigger definition (DDL, whose body the analyzer
 * does not walk) its result is complemented by a conservative regex scan that also handles procedural bodies.
 * Writes are INSERT INTO / UPDATE / DELETE FROM / MERGE INTO targets; everything after FROM / JOIN is a read.
 */
public final class SqlRefs {
    private static final Pattern WRITE = Pattern.compile("\\b(?:INSERT\\s+(?:ALL\\s+)?INTO|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|DELETE|MERGE\\s+INTO|TRUNCATE\\s+TABLE)\\s+((?:[\\w$#\"\\[\\]]+\\.)?[\\w$#\"\\[\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ = Pattern.compile("\\b(?:FROM|JOIN)\\s+((?:[\\w$#\"\\[\\]]+\\.)?[\\w$#\"\\[\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[\\w$#.\"\\[\\]]+");
    private static final java.util.Set<String> NOISE = java.util.Set.of("SELECT", "DUAL", "VALUES", "SET", "WHERE", "ONLY", "TABLE", "INTO", "LATERAL", "UNNEST", "GENERATE_SERIES", "ALL", "DELETE", "UPDATE", "INSERT", "MERGE", "CURSOR");

    /** Thread-safe and memoising (LRU), shared by all crawlers. */
    private static final SqlAnalyzer ANALYZER = new SqlAnalyzer();

    private SqlRefs() {}

    public record Ref(String schema, String name, boolean write) {}

    public static List<Ref> extract(String sql, Engine engine) {
        if (sql == null || sql.isBlank()) return List.of();
        Map<String, Ref> out = new LinkedHashMap<>();
        SqlAnalysis analysis = ANALYZER.analyze(sql, engine == null ? Engine.OTHER : engine);
        for (TableAccess t : analysis.tables()) put(out, t.schema(), t.name(), t.access() == AccessType.WRITE);
        // The regex scan complements the analyzer wherever the text is procedural: whatever it could not parse (parseOk == false)
        // and routine / trigger definitions. SqlAnalyzer reports parseOk == true for "CREATE PROCEDURE|FUNCTION|TRIGGER ... AS <body>"
        // (operation DDL) but only walks the CREATE itself: the T-SQL body's INSERT INTO is missing from its tables.
        if (!analysis.parseOk() || analysis.operation() == SqlOperation.DDL) regexScan(out, sql);
        return new ArrayList<>(out.values());
    }

    private static void regexScan(Map<String, Ref> out, String sql) {
        String text = stripComments(sql);
        Matcher w = WRITE.matcher(text);
        while (w.find()) put(out, null, w.group(1), true);
        Matcher r = READ.matcher(text);
        while (r.find()) {
            String ident = r.group(1);
            if (NOISE.contains(unq(ident).toUpperCase(Locale.ROOT))) continue;
            put(out, null, ident, false);
        }
    }

    private static void put(Map<String, Ref> out, String schema, String ident, boolean write) {
        String s = schema, n = ident;
        if (n == null) return;
        n = n.trim();
        if (s == null && n.contains(".")) {
            int i = n.indexOf('.');
            s = n.substring(0, i);
            n = n.substring(i + 1);
            if (n.contains(".")) return; // db.schema.table or deeper: ignore
        }
        s = s == null ? null : unq(s);
        n = unq(n);
        if (n.isEmpty() || !Character.isLetter(n.charAt(0)) && n.charAt(0) != '_') return;
        if (NOISE.contains(n.toUpperCase(Locale.ROOT))) return;
        String key = (s == null ? "" : s.toUpperCase(Locale.ROOT) + ".") + n.toUpperCase(Locale.ROOT);
        Ref prev = out.get(key);
        if (prev == null || (write && !prev.write())) out.put(key, new Ref(s, n, write || (prev != null && prev.write())));
    }

    private static String unq(String s) {
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("[") && t.endsWith("]")))) return t.substring(1, t.length() - 1);
        return t;
    }

    static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    /** Splits a package body into (memberName → text) sections by PROCEDURE/FUNCTION headers. */
    public static Map<String, String> splitPackageMembers(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        Pattern header = Pattern.compile("(?im)^\\s*(?:PROCEDURE|FUNCTION)\\s+(\"?[\\w$#]+\"?)");
        Matcher m = header.matcher(body);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) { starts.add(new int[]{m.start()}); names.add(unq(m.group(1))); }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            out.merge(names.get(i).toUpperCase(Locale.ROOT), body.substring(from, to), (a, b) -> a + "\n" + b);
        }
        return out;
    }
}
