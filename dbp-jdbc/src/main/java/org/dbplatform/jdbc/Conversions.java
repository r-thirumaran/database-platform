package org.dbplatform.jdbc;

import org.dbplatform.protocol.TypedNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.UUID;

/**
 * JDBC conversions between the Java objects produced by {@code Values.decode} ({@link Boolean}, boxed
 * numbers, {@link BigDecimal}, {@link String}, {@code byte[]}, {@link LocalDate}, {@link LocalTime},
 * {@link LocalDateTime}, {@link OffsetDateTime}, {@link OffsetTime}, {@code null}) and the types requested
 * through the {@code getXxx} accessors, plus the reverse direction for {@code setObject(i, x, targetType)}.
 *
 * <p>Numeric overflow raises SQLState {@code 22003}; an unparsable or impossible conversion raises
 * {@code 22018}.</p>
 */
final class Conversions {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Conversions() {
    }

    // ---------------------------------------------------------------- strings

    static String toString(Object v) {
        return switch (v) {
            case null -> null;
            case String s -> s;
            case BigDecimal bd -> bd.toPlainString();
            case byte[] b -> hex(b);
            case LocalDate d -> d.toString();
            case LocalTime t -> formatTime(t);
            case LocalDateTime ldt -> Timestamp.valueOf(ldt).toString();
            case OffsetDateTime odt -> Timestamp.valueOf(odt.toLocalDateTime()).toString() + offsetId(odt.getOffset());
            case OffsetTime ot -> formatTime(ot.toLocalTime()) + offsetId(ot.getOffset());
            default -> v.toString();
        };
    }

    private static String formatTime(LocalTime t) {
        String base = TIME_FORMAT.format(t);
        if (t.getNano() == 0) {
            return base;
        }
        String frac = String.format("%09d", t.getNano());
        int end = frac.length();
        while (end > 1 && frac.charAt(end - 1) == '0') {
            end--;
        }
        return base + "." + frac.substring(0, end);
    }

    private static String offsetId(ZoneOffset off) {
        return off.getTotalSeconds() == 0 ? "+00:00" : off.getId();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- booleans and numbers

    static boolean toBoolean(Object v) throws SQLException {
        switch (v) {
            case null -> {
                return false;
            }
            case Boolean b -> {
                return b;
            }
            case BigDecimal bd -> {
                return bd.signum() != 0;
            }
            case Double d -> {
                return d != 0;
            }
            case Float f -> {
                return f != 0;
            }
            case Number n -> {
                return n.longValue() != 0;
            }
            case String s -> {
                String t = s.trim().toLowerCase();
                switch (t) {
                    case "true", "t", "1", "yes", "y", "on" -> {
                        return true;
                    }
                    case "false", "f", "0", "no", "n", "off", "" -> {
                        return false;
                    }
                    default -> {
                        try {
                            return new BigDecimal(t).signum() != 0;
                        } catch (NumberFormatException e) {
                            throw DbpSqlExceptions.cannotConvert(v, "boolean");
                        }
                    }
                }
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "boolean");
        }
    }

    static long toLong(Object v) throws SQLException {
        switch (v) {
            case null -> {
                return 0L;
            }
            case Long l -> {
                return l;
            }
            case Integer i -> {
                return i;
            }
            case Short s -> {
                return s;
            }
            case Byte b -> {
                return b;
            }
            case Boolean b -> {
                return b ? 1L : 0L;
            }
            case BigDecimal bd -> {
                try {
                    return bd.setScale(0, RoundingMode.DOWN).longValueExact();
                } catch (ArithmeticException e) {
                    throw DbpSqlExceptions.outOfRange(bd, "long");
                }
            }
            case BigInteger bi -> {
                try {
                    return bi.longValueExact();
                } catch (ArithmeticException e) {
                    throw DbpSqlExceptions.outOfRange(bi, "long");
                }
            }
            case Double d -> {
                return doubleToLong(d, v);
            }
            case Float f -> {
                return doubleToLong(f, v);
            }
            case Number n -> {
                return toLong(new BigDecimal(n.toString()));
            }
            case String s -> {
                String t = s.trim();
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    try {
                        return toLong(new BigDecimal(t));
                    } catch (NumberFormatException e2) {
                        throw DbpSqlExceptions.cannotConvert(v, "long", e2);
                    }
                }
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "long");
        }
    }

    private static long doubleToLong(double d, Object original) throws SQLException {
        if (Double.isNaN(d) || Double.isInfinite(d) || d >= 0x1p63 || d < -0x1p63) {
            throw DbpSqlExceptions.outOfRange(original, "long");
        }
        return (long) d;
    }

    static int toInt(Object v) throws SQLException {
        long l = toLong(v);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw DbpSqlExceptions.outOfRange(v, "int");
        }
        return (int) l;
    }

    static short toShort(Object v) throws SQLException {
        long l = toLong(v);
        if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
            throw DbpSqlExceptions.outOfRange(v, "short");
        }
        return (short) l;
    }

    static byte toByte(Object v) throws SQLException {
        long l = toLong(v);
        if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) {
            throw DbpSqlExceptions.outOfRange(v, "byte");
        }
        return (byte) l;
    }

    static double toDouble(Object v) throws SQLException {
        switch (v) {
            case null -> {
                return 0d;
            }
            case Double d -> {
                return d;
            }
            case Float f -> {
                return f;
            }
            case Boolean b -> {
                return b ? 1d : 0d;
            }
            case Number n -> {
                return n.doubleValue();
            }
            case String s -> {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw DbpSqlExceptions.cannotConvert(v, "double", e);
                }
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "double");
        }
    }

    static float toFloat(Object v) throws SQLException {
        double d = toDouble(v);
        float f = (float) d;
        if (Float.isInfinite(f) && !Double.isInfinite(d)) {
            throw DbpSqlExceptions.outOfRange(v, "float");
        }
        return f;
    }

    static BigDecimal toBigDecimal(Object v) throws SQLException {
        switch (v) {
            case null -> {
                return null;
            }
            case BigDecimal bd -> {
                return bd;
            }
            case BigInteger bi -> {
                return new BigDecimal(bi);
            }
            case Long l -> {
                return BigDecimal.valueOf(l);
            }
            case Integer i -> {
                return BigDecimal.valueOf(i);
            }
            case Short s -> {
                return BigDecimal.valueOf(s);
            }
            case Byte b -> {
                return BigDecimal.valueOf(b);
            }
            case Boolean b -> {
                return b ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    throw DbpSqlExceptions.outOfRange(v, "BigDecimal");
                }
                return BigDecimal.valueOf(d);
            }
            case Float f -> {
                if (f.isNaN() || f.isInfinite()) {
                    throw DbpSqlExceptions.outOfRange(v, "BigDecimal");
                }
                return new BigDecimal(f.toString());
            }
            case Number n -> {
                try {
                    return new BigDecimal(n.toString());
                } catch (NumberFormatException e) {
                    throw DbpSqlExceptions.cannotConvert(v, "BigDecimal", e);
                }
            }
            case String s -> {
                try {
                    return new BigDecimal(s.trim());
                } catch (NumberFormatException e) {
                    throw DbpSqlExceptions.cannotConvert(v, "BigDecimal", e);
                }
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "BigDecimal");
        }
    }

    static BigDecimal toBigDecimal(Object v, int scale) throws SQLException {
        BigDecimal bd = toBigDecimal(v);
        return bd == null ? null : bd.setScale(scale, RoundingMode.HALF_UP);
    }

    static BigInteger toBigInteger(Object v) throws SQLException {
        BigDecimal bd = toBigDecimal(v);
        return bd == null ? null : bd.setScale(0, RoundingMode.DOWN).toBigInteger();
    }

    // ---------------------------------------------------------------- binary

    static byte[] toBytes(Object v) throws SQLException {
        return switch (v) {
            case null -> null;
            case byte[] b -> b.clone();
            case String s -> s.getBytes(StandardCharsets.UTF_8);
            default -> throw DbpSqlExceptions.cannotConvert(v, "byte[]");
        };
    }

    // ---------------------------------------------------------------- temporal (java.time)

    /**
     * Returns the local date of {@code v}. {@code zone} is the zone in which an {@link OffsetDateTime} is
     * expressed before taking its date part; {@code null} keeps the value's own offset.
     */
    static LocalDate toLocalDate(Object v, ZoneId zone) throws SQLException {
        return switch (v) {
            case null -> null;
            case LocalDate d -> d;
            case LocalDateTime ldt -> ldt.toLocalDate();
            case OffsetDateTime odt -> (zone == null ? odt : odt.atZoneSameInstant(zone)).toLocalDate();
            case String s -> parseLocalDate(s, v);
            default -> throw DbpSqlExceptions.cannotConvert(v, "date");
        };
    }

    static LocalTime toLocalTime(Object v, ZoneId zone) throws SQLException {
        return switch (v) {
            case null -> null;
            case LocalTime t -> t;
            case LocalDateTime ldt -> ldt.toLocalTime();
            case OffsetDateTime odt -> (zone == null ? odt : odt.atZoneSameInstant(zone)).toLocalTime();
            case OffsetTime ot -> zone == null ? ot.toLocalTime()
                    : ot.atDate(LocalDate.EPOCH).atZoneSameInstant(zone).toLocalTime();
            case String s -> parseLocalTime(s, v);
            default -> throw DbpSqlExceptions.cannotConvert(v, "time");
        };
    }

    static LocalDateTime toLocalDateTime(Object v, ZoneId zone) throws SQLException {
        return switch (v) {
            case null -> null;
            case LocalDateTime ldt -> ldt;
            case LocalDate d -> d.atStartOfDay();
            case LocalTime t -> LocalDate.EPOCH.atTime(t);
            case OffsetDateTime odt -> (zone == null ? odt : odt.atZoneSameInstant(zone)).toLocalDateTime();
            case OffsetTime ot -> (zone == null ? ot.atDate(LocalDate.EPOCH)
                    : ot.atDate(LocalDate.EPOCH).atZoneSameInstant(zone)).toLocalDateTime();
            case String s -> parseLocalDateTime(s, v);
            default -> throw DbpSqlExceptions.cannotConvert(v, "timestamp");
        };
    }

    /**
     * Returns {@code v} as an {@link OffsetDateTime}; local values are interpreted in {@code zone}
     * ({@code null} = system default).
     */
    static OffsetDateTime toOffsetDateTime(Object v, ZoneId zone) throws SQLException {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return switch (v) {
            case null -> null;
            case OffsetDateTime odt -> odt;
            case OffsetTime ot -> ot.atDate(LocalDate.EPOCH);
            case LocalDateTime ldt -> ldt.atZone(z).toOffsetDateTime();
            case LocalDate d -> d.atStartOfDay(z).toOffsetDateTime();
            case LocalTime t -> LocalDate.EPOCH.atTime(t).atZone(z).toOffsetDateTime();
            case String s -> parseOffsetDateTime(s, v, z);
            default -> throw DbpSqlExceptions.cannotConvert(v, "timestamp with time zone");
        };
    }

    static OffsetTime toOffsetTime(Object v, ZoneId zone) throws SQLException {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return switch (v) {
            case null -> null;
            case OffsetTime ot -> ot;
            case OffsetDateTime odt -> odt.toOffsetTime();
            case LocalTime t -> t.atOffset(z.getRules().getOffset(LocalDate.EPOCH.atTime(t)));
            case LocalDateTime ldt -> ldt.atZone(z).toOffsetDateTime().toOffsetTime();
            case String s -> {
                try {
                    yield OffsetTime.parse(s.trim());
                } catch (DateTimeException e) {
                    yield toOffsetTime(parseLocalTime(s, v), z);
                }
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "time with time zone");
        };
    }

    static Instant toInstant(Object v, ZoneId zone) throws SQLException {
        OffsetDateTime odt = toOffsetDateTime(v, zone);
        return odt == null ? null : odt.toInstant();
    }

    private static LocalDate parseLocalDate(String s, Object original) throws SQLException {
        String t = s.trim();
        try {
            return LocalDate.parse(t);
        } catch (DateTimeException e) {
            try {
                return java.sql.Date.valueOf(t).toLocalDate();
            } catch (IllegalArgumentException e2) {
                try {
                    return parseLocalDateTime(t, original).toLocalDate();
                } catch (SQLException e3) {
                    throw DbpSqlExceptions.cannotConvert(original, "date", e);
                }
            }
        }
    }

    private static LocalTime parseLocalTime(String s, Object original) throws SQLException {
        String t = s.trim();
        try {
            return LocalTime.parse(t);
        } catch (DateTimeException e) {
            try {
                return java.sql.Time.valueOf(t).toLocalTime();
            } catch (IllegalArgumentException e2) {
                try {
                    return parseLocalDateTime(t, original).toLocalTime();
                } catch (SQLException e3) {
                    throw DbpSqlExceptions.cannotConvert(original, "time", e);
                }
            }
        }
    }

    private static LocalDateTime parseLocalDateTime(String s, Object original) throws SQLException {
        String t = s.trim();
        try {
            return Timestamp.valueOf(t).toLocalDateTime();
        } catch (IllegalArgumentException ignored) {
            // not JDBC escape format
        }
        try {
            return LocalDateTime.parse(t);
        } catch (DateTimeException ignored) {
            // not ISO
        }
        try {
            return LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (DateTimeException ignored) {
            // not ISO with space
        }
        try {
            return OffsetDateTime.parse(t).toLocalDateTime();
        } catch (DateTimeException ignored) {
            // not offset
        }
        try {
            return ZonedDateTime.parse(t).toLocalDateTime();
        } catch (DateTimeException ignored) {
            // not zoned
        }
        try {
            return LocalDate.parse(t).atStartOfDay();
        } catch (DateTimeException ignored) {
            // not a date
        }
        try {
            return java.sql.Date.valueOf(t).toLocalDate().atStartOfDay();
        } catch (IllegalArgumentException e) {
            throw DbpSqlExceptions.cannotConvert(original, "timestamp", e);
        }
    }

    private static OffsetDateTime parseOffsetDateTime(String s, Object original, ZoneId zone) throws SQLException {
        String t = s.trim();
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(t).toOffsetDateTime();
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(t.replace(' ', 'T'));
        } catch (DateTimeException ignored) {
            // fall through
        }
        return parseLocalDateTime(t, original).atZone(zone).toOffsetDateTime();
    }

    // ---------------------------------------------------------------- temporal (java.sql)

    private static ZoneId zoneOf(Calendar cal) {
        return cal == null ? ZoneId.systemDefault() : cal.getTimeZone().toZoneId();
    }

    static java.sql.Date toDate(Object v, Calendar cal) throws SQLException {
        ZoneId zone = zoneOf(cal);
        LocalDate d = toLocalDate(v, zone);
        return d == null ? null : new java.sql.Date(d.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    static java.sql.Time toTime(Object v, Calendar cal) throws SQLException {
        ZoneId zone = zoneOf(cal);
        LocalTime t = toLocalTime(v, zone);
        return t == null ? null : new java.sql.Time(LocalDate.EPOCH.atTime(t).atZone(zone).toInstant().toEpochMilli());
    }

    static Timestamp toTimestamp(Object v, Calendar cal) throws SQLException {
        ZoneId zone = zoneOf(cal);
        if (v instanceof OffsetDateTime odt) {
            return Timestamp.from(odt.toInstant());
        }
        if (v instanceof OffsetTime ot) {
            return Timestamp.from(ot.atDate(LocalDate.EPOCH).toInstant());
        }
        if (v instanceof String s) {
            String t = s.trim();
            try {
                return Timestamp.from(OffsetDateTime.parse(t).toInstant());
            } catch (DateTimeException ignored) {
                // not an offset timestamp
            }
        }
        LocalDateTime ldt = toLocalDateTime(v, zone);
        return ldt == null ? null : Timestamp.from(ldt.atZone(zone).toInstant());
    }

    // ---------------------------------------------------------------- getObject

    /** Maps a decoded value to the JDBC-standard Java class for {@code getObject(i)}. */
    static Object toJdbcObject(Object v) {
        return switch (v) {
            case null -> null;
            case LocalDate d -> java.sql.Date.valueOf(d);
            case LocalTime t -> new java.sql.Time(LocalDate.EPOCH.atTime(t).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli());
            case LocalDateTime ldt -> Timestamp.valueOf(ldt);
            case byte[] b -> b.clone();
            default -> v;
        };
    }

    /** Implements {@code getObject(i, Class)}. */
    @SuppressWarnings("unchecked")
    static <T> T convert(Object v, Class<T> type) throws SQLException {
        if (type == null) {
            throw DbpSqlExceptions.invalidArgument("type must not be null");
        }
        if (v == null) {
            if (type.isPrimitive()) {
                throw DbpSqlExceptions.cannotConvert(null, type.getName());
            }
            return null;
        }
        Object r;
        if (type == String.class) {
            r = toString(v);
        } else if (type == Integer.class || type == int.class) {
            r = toInt(v);
        } else if (type == Long.class || type == long.class) {
            r = toLong(v);
        } else if (type == Short.class || type == short.class) {
            r = toShort(v);
        } else if (type == Byte.class || type == byte.class) {
            r = toByte(v);
        } else if (type == Double.class || type == double.class) {
            r = toDouble(v);
        } else if (type == Float.class || type == float.class) {
            r = toFloat(v);
        } else if (type == Boolean.class || type == boolean.class) {
            r = toBoolean(v);
        } else if (type == BigDecimal.class) {
            r = toBigDecimal(v);
        } else if (type == BigInteger.class) {
            r = toBigInteger(v);
        } else if (type == Number.class) {
            r = v instanceof Number ? v : toBigDecimal(v);
        } else if (type == byte[].class) {
            r = toBytes(v);
        } else if (type == java.sql.Date.class) {
            r = toDate(v, null);
        } else if (type == java.sql.Time.class) {
            r = toTime(v, null);
        } else if (type == Timestamp.class) {
            r = toTimestamp(v, null);
        } else if (type == java.util.Date.class) {
            Timestamp ts = toTimestamp(v, null);
            r = ts == null ? null : new java.util.Date(ts.getTime());
        } else if (type == LocalDate.class) {
            r = toLocalDate(v, null);
        } else if (type == LocalTime.class) {
            r = toLocalTime(v, null);
        } else if (type == LocalDateTime.class) {
            r = toLocalDateTime(v, null);
        } else if (type == OffsetDateTime.class) {
            r = toOffsetDateTime(v, null);
        } else if (type == ZonedDateTime.class) {
            OffsetDateTime odt = toOffsetDateTime(v, null);
            r = odt == null ? null : odt.toZonedDateTime();
        } else if (type == OffsetTime.class) {
            r = toOffsetTime(v, null);
        } else if (type == Instant.class) {
            r = toInstant(v, null);
        } else if (type == UUID.class) {
            r = toUuid(v);
        } else if (type == Blob.class) {
            r = new DbpBlob(toBytes(v));
        } else if (type == Clob.class || type == java.sql.NClob.class) {
            r = new DbpClob(toString(v));
        } else if (type == Object.class) {
            r = toJdbcObject(v);
        } else if (type.isInstance(v)) {
            r = v;
        } else {
            throw DbpSqlExceptions.notSupported("conversion to " + type.getName());
        }
        return (T) r;
    }

    static UUID toUuid(Object v) throws SQLException {
        switch (v) {
            case null -> {
                return null;
            }
            case UUID u -> {
                return u;
            }
            case String s -> {
                try {
                    return UUID.fromString(s.trim());
                } catch (IllegalArgumentException e) {
                    throw DbpSqlExceptions.cannotConvert(v, "UUID", e);
                }
            }
            case byte[] b -> {
                if (b.length != 16) {
                    throw DbpSqlExceptions.cannotConvert(v, "UUID");
                }
                long msb = 0;
                long lsb = 0;
                for (int i = 0; i < 8; i++) {
                    msb = (msb << 8) | (b[i] & 0xFF);
                }
                for (int i = 8; i < 16; i++) {
                    lsb = (lsb << 8) | (b[i] & 0xFF);
                }
                return new UUID(msb, lsb);
            }
            default -> throw DbpSqlExceptions.cannotConvert(v, "UUID");
        }
    }

    // ---------------------------------------------------------------- parameters (setObject)

    /**
     * Normalises an object passed to {@code setObject(i, x)} into something {@code Values.encode} understands
     * exactly as the matching {@code setXxx} would: LOB locators and streams are materialised.
     */
    static Object toParameter(Object x) throws SQLException {
        return switch (x) {
            case null -> null;
            case Blob b -> readBlob(b);
            case Clob c -> readClob(c);
            case SQLXML xml -> xml.getString();
            case InputStream in -> readFully(in, -1);
            case Reader r -> readFully(r, -1);
            case Character c -> c.toString();
            case java.net.URL u -> u.toString();
            default -> x;
        };
    }

    /**
     * Converts an object passed to {@code setObject(i, x, targetSqlType[, scale])} to the Java type the
     * matching {@code setXxx} would use, so the wire tag follows the requested JDBC type.
     */
    static Object toParameter(Object x, int targetSqlType, Integer scale) throws SQLException {
        Object v = toParameter(x);
        if (v == null) {
            return new TypedNull(targetSqlType);
        }
        Object decoded = toDecodedShape(v);
        return switch (targetSqlType) {
            case Types.BIT, Types.BOOLEAN -> toBoolean(decoded);
            case Types.TINYINT -> toByte(decoded);
            case Types.SMALLINT -> toShort(decoded);
            case Types.INTEGER -> toInt(decoded);
            case Types.BIGINT -> toLong(decoded);
            case Types.REAL -> toFloat(decoded);
            case Types.FLOAT, Types.DOUBLE -> toDouble(decoded);
            case Types.NUMERIC, Types.DECIMAL -> scale == null ? toBigDecimal(decoded) : toBigDecimal(decoded, scale);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML -> toString(decoded);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> toBytes(decoded);
            case Types.DATE -> toLocalDate(decoded, ZoneId.systemDefault());
            case Types.TIME -> toLocalTime(decoded, ZoneId.systemDefault());
            case Types.TIME_WITH_TIMEZONE -> toOffsetTime(decoded, null);
            case Types.TIMESTAMP -> toLocalDateTime(decoded, ZoneId.systemDefault());
            case Types.TIMESTAMP_WITH_TIMEZONE -> toOffsetDateTime(decoded, null);
            case Types.NULL -> new TypedNull(Types.NULL);
            case Types.ARRAY, Types.STRUCT, Types.REF, Types.ROWID, Types.DATALINK, Types.REF_CURSOR ->
                    throw DbpSqlExceptions.notSupported("setObject with target type " + targetSqlType);
            default -> v;
        };
    }

    /** Turns java.sql / java.util temporal inputs into the java.time shapes the converters accept. */
    private static Object toDecodedShape(Object v) {
        return switch (v) {
            case Timestamp ts -> ts.toLocalDateTime();
            case java.sql.Date d -> d.toLocalDate();
            case java.sql.Time t -> t.toLocalTime();
            case java.util.Date d -> LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
            case Calendar c -> OffsetDateTime.ofInstant(c.toInstant(), c.getTimeZone().toZoneId());
            case ZonedDateTime z -> z.toOffsetDateTime();
            case Instant i -> i.atOffset(ZoneOffset.UTC);
            case UUID u -> u.toString();
            default -> v;
        };
    }

    // ---------------------------------------------------------------- streams and LOBs

    static byte[] readBlob(Blob b) throws SQLException {
        return b == null ? null : b.getBytes(1, (int) Math.min(Integer.MAX_VALUE, b.length()));
    }

    static String readClob(Clob c) throws SQLException {
        return c == null ? null : c.getSubString(1, (int) Math.min(Integer.MAX_VALUE, c.length()));
    }

    static byte[] readFully(InputStream in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        try {
            if (length < 0) {
                return in.readAllBytes();
            }
            return in.readNBytes((int) Math.min(Integer.MAX_VALUE, length));
        } catch (IOException e) {
            throw new SQLException("cannot read parameter stream: " + e.getMessage(), "HY000", e);
        }
    }

    static String readFully(Reader r, long length) throws SQLException {
        if (r == null) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            long remaining = length < 0 ? Long.MAX_VALUE : length;
            int n;
            while (remaining > 0 && (n = r.read(buf, 0, (int) Math.min(buf.length, remaining))) >= 0) {
                sb.append(buf, 0, n);
                remaining -= n;
            }
            return sb.toString();
        } catch (IOException e) {
            throw new SQLException("cannot read parameter stream: " + e.getMessage(), "HY000", e);
        }
    }

    static String readAscii(InputStream in, long length) throws SQLException {
        byte[] b = readFully(in, length);
        return b == null ? null : new String(b, StandardCharsets.US_ASCII);
    }

}
