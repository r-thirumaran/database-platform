package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Minimal JDBC helper so crawlers stay declarative (and testable with a stub connection). */
public final class Jdbc {
    private Jdbc() {}

    @FunctionalInterface
    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    @FunctionalInterface
    public interface RowConsumer { void accept(ResultSet rs) throws SQLException; }

    public static <T> List<T> query(Connection c, String sql, List<Object> params, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        forEach(c, sql, params, rs -> out.add(mapper.map(rs)));
        return out;
    }

    public static void forEach(Connection c, String sql, List<Object> params, RowConsumer consumer) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Object p : params) ps.setObject(i++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) consumer.accept(rs);
            }
        }
    }

    /** Oracle: run against DBA_* views first, fall back to ALL_* when ORA-00942 (no privilege / view missing). */
    public static void forEachDbaOrAll(Connection c, String dbaSql, List<Object> params, RowConsumer consumer) throws SQLException {
        try {
            forEach(c, dbaSql, params, consumer);
        } catch (SQLException e) {
            if (e.getErrorCode() == 942 || (e.getMessage() != null && e.getMessage().contains("ORA-00942"))) {
                forEach(c, DBA_PREFIX.matcher(dbaSql).replaceAll("ALL_"), params, consumer);
            } else {
                throw e;
            }
        }
    }

    private static final Pattern DBA_PREFIX = Pattern.compile("\\bDBA_");

    /** {@code IN (?, ?, ...)} placeholders for a list. */
    public static String placeholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.append(")").toString();
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof Instant in) return in;
        if (o instanceof java.util.Date d) return d.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt) return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        try {
            Timestamp ts = rs.getTimestamp(col);
            return ts == null ? null : ts.toInstant();
        } catch (SQLException e) {
            return null;
        }
    }

    public static Integer integer(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    public static Long longValue(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    public static String string(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) return null;
        if (o instanceof String s) return s;
        if (o instanceof java.sql.Clob clob) {
            try { return clob.getSubString(1, (int) Math.min(clob.length(), 200_000)); } catch (SQLException e) { return null; }
        }
        return rs.getString(col);
    }
}
