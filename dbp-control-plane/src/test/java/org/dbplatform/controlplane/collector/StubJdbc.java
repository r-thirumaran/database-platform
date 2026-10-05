package org.dbplatform.controlplane.collector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link Connection} stub for crawler tests: SQL text is matched against registered markers (substrings,
 * case-insensitive, whitespace-normalised) and canned rows are returned through a dynamic-proxy ResultSet.
 */
public final class StubJdbc {
    private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    private final Map<String, SQLException> failures = new LinkedHashMap<>();
    public final List<String> executed = new ArrayList<>();

    public StubJdbc on(String marker, List<Map<String, Object>> result) { rows.put(norm(marker), result); return this; }
    public StubJdbc fail(String marker, SQLException e) { failures.put(norm(marker), e); return this; }

    public static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(((String) kv[i]).toUpperCase(Locale.ROOT), kv[i + 1]);
        return m;
    }

    private static String norm(String s) { return s.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT); }

    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement": return statement((String) args[0]);
                case "close", "commit", "rollback", "setAutoCommit": return null;
                case "isClosed": return false;
                case "toString": return "StubConnection";
                case "hashCode": return 1;
                case "equals": return proxy == args[0];
                default: return defaultValue(method);
            }
        });
    }

    private PreparedStatement statement(String sql) {
        String n = norm(sql);
        executed.add(sql);
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeQuery": {
                    for (Map.Entry<String, SQLException> f : failures.entrySet()) if (n.contains(f.getKey())) throw f.getValue();
                    for (Map.Entry<String, List<Map<String, Object>>> e : rows.entrySet()) if (n.contains(e.getKey())) return resultSet(e.getValue());
                    throw new SQLException("no stub result registered for: " + sql);
                }
                case "setObject", "setString", "setInt", "setLong", "setTimestamp", "close", "setFetchSize", "setQueryTimeout": return null;
                case "toString": return "StubStatement[" + sql + "]";
                case "hashCode": return sql.hashCode();
                case "equals": return proxy == args[0];
                default: return defaultValue(method);
            }
        });
    }

    private ResultSet resultSet(List<Map<String, Object>> data) {
        final int[] idx = {-1};
        final boolean[] wasNull = {false};
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("next")) return ++idx[0] < data.size();
            if (name.equals("close")) return null;
            if (name.equals("wasNull")) return wasNull[0];
            if (name.equals("toString")) return "StubResultSet";
            if (name.equals("hashCode")) return 2;
            if (name.equals("equals")) return proxy == args[0];
            if (name.startsWith("get") && args != null && args.length == 1 && args[0] instanceof String col) {
                Object v = data.get(idx[0]).get(col.toUpperCase(Locale.ROOT));
                wasNull[0] = v == null;
                return convert(v, method.getReturnType());
            }
            return defaultValue(method);
        };
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, h);
    }

    private static Object convert(Object v, Class<?> type) {
        if (type == Object.class) return v instanceof Instant i ? Timestamp.from(i) : v;
        if (v == null) return type.isPrimitive() ? defaultPrimitive(type) : null;
        if (type == String.class) return v.toString();
        if (type == int.class) return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
        if (type == long.class) return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
        if (type == boolean.class) return v instanceof Boolean b ? b : v instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(v.toString());
        if (type == double.class) return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        if (type == Timestamp.class) return v instanceof Instant i ? Timestamp.from(i) : v;
        return v;
    }

    private static Object defaultValue(Method m) {
        Class<?> t = m.getReturnType();
        return t.isPrimitive() ? defaultPrimitive(t) : null;
    }

    private static Object defaultPrimitive(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        return null;
    }
}
