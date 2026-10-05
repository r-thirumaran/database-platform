package org.dbplatform.controlplane.collector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.TableAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Table references in SQL / PL-SQL / T-SQL / plpgsql text. Uses {@code org.dbplatform.common.sql.SqlAnalyzer}
 * (JSqlParser based) when the class is on the classpath, and always falls back to a conservative regex scan
 * that also handles procedural bodies the parser rejects. Writes are INSERT INTO / UPDATE / DELETE FROM /
 * MERGE INTO targets; everything after FROM / JOIN is a read.
 */
public final class SqlRefs {
    private static final Logger log = LoggerFactory.getLogger(SqlRefs.class);
    private static final Pattern WRITE = Pattern.compile("\\b(?:INSERT\\s+(?:ALL\\s+)?INTO|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|DELETE|MERGE\\s+INTO|TRUNCATE\\s+TABLE)\\s+((?:[\\w$#\"\\[\\]]+\\.)?[\\w$#\"\\[\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ = Pattern.compile("\\b(?:FROM|JOIN)\\s+((?:[\\w$#\"\\[\\]]+\\.)?[\\w$#\"\\[\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[\\w$#.\"\\[\\]]+");
    private static final java.util.Set<String> NOISE = java.util.Set.of("SELECT", "DUAL", "VALUES", "SET", "WHERE", "ONLY", "TABLE", "INTO", "LATERAL", "UNNEST", "GENERATE_SERIES", "ALL", "DELETE", "UPDATE", "INSERT", "MERGE", "CURSOR");

    private static volatile Object analyzer;
    private static volatile Method analyzeMethod;
    private static volatile Method tablesMethod;
    private static volatile boolean probed;

    private SqlRefs() {}

    public record Ref(String schema, String name, boolean write) {}

    public static List<Ref> extract(String sql, Engine engine) {
        if (sql == null || sql.isBlank()) return List.of();
        Map<String, Ref> out = new LinkedHashMap<>();
        List<TableAccess> parsed = analyzeWithCommon(sql, engine);
        if (parsed != null) {
            for (TableAccess t : parsed) put(out, t.schema(), t.name(), t.access() == AccessType.WRITE);
        }
        // regex pass (always: procedural bodies contain many statements, parsers see only the first)
        String text = stripComments(sql);
        Matcher w = WRITE.matcher(text);
        while (w.find()) put(out, null, w.group(1), true);
        Matcher r = READ.matcher(text);
        while (r.find()) {
            String ident = r.group(1);
            if (NOISE.contains(unq(ident).toUpperCase(Locale.ROOT))) continue;
            put(out, null, ident, false);
        }
        return new ArrayList<>(out.values());
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

    @SuppressWarnings("unchecked")
    private static List<TableAccess> analyzeWithCommon(String sql, Engine engine) {
        if (!probed) {
            synchronized (SqlRefs.class) {
                if (!probed) {
                    try {
                        Class<?> cls = Class.forName("org.dbplatform.common.sql.SqlAnalyzer");
                        analyzer = cls.getConstructor().newInstance();
                        analyzeMethod = cls.getMethod("analyze", String.class, Engine.class);
                        tablesMethod = analyzeMethod.getReturnType().getMethod("tables");
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log.info("org.dbplatform.common.sql.SqlAnalyzer not available ({}); using regex table extraction only", e.toString());
                    }
                    probed = true;
                }
            }
        }
        if (analyzeMethod == null || sql.length() > 100_000) return null;
        try {
            Object analysis = analyzeMethod.invoke(analyzer, sql, engine == null ? Engine.OTHER : engine);
            return (List<TableAccess>) tablesMethod.invoke(analysis);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
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
