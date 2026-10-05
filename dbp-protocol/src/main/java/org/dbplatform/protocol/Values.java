package org.dbplatform.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.Calendar;
import java.util.UUID;

/**
 * Encoding and decoding of tagged {@code Value}s (section 2 of the specification) and the server-side
 * mapping from {@link java.sql.Types} to {@link ValueTag}.
 *
 * <h2>Encoding (Java object → tag)</h2>
 * <table>
 *   <caption>Runtime type to tag</caption>
 *   <tr><th>Java type</th><th>tag</th></tr>
 *   <tr><td>{@code null}</td><td>NULL</td></tr>
 *   <tr><td>{@link TypedNull}</td><td>TYPED_NULL</td></tr>
 *   <tr><td>{@link Boolean}</td><td>BOOLEAN</td></tr>
 *   <tr><td>{@link Byte} / {@link Short} / {@link Integer} / {@link Long}</td><td>BYTE / SHORT / INT / LONG</td></tr>
 *   <tr><td>{@link Float} / {@link Double}</td><td>FLOAT / DOUBLE</td></tr>
 *   <tr><td>{@link BigDecimal} / {@link BigInteger} / other {@link Number}</td><td>DECIMAL (plain string)</td></tr>
 *   <tr><td>{@link String} / {@link CharSequence} / {@link Character} / {@link UUID}</td><td>STRING</td></tr>
 *   <tr><td>{@code byte[]}</td><td>BYTES</td></tr>
 *   <tr><td>{@link java.sql.Date} / {@link LocalDate}</td><td>DATE</td></tr>
 *   <tr><td>{@link java.sql.Time} / {@link LocalTime}</td><td>TIME</td></tr>
 *   <tr><td>{@link java.sql.Timestamp} / {@link LocalDateTime} / other {@link java.util.Date} (system zone)</td><td>TIMESTAMP</td></tr>
 *   <tr><td>{@link OffsetDateTime} / {@link ZonedDateTime} / {@link Instant} (UTC) / {@link Calendar}</td><td>TIMESTAMP_TZ</td></tr>
 *   <tr><td>{@link OffsetTime}</td><td>TIME_TZ</td></tr>
 *   <tr><td>anything else</td><td>STRING via {@code toString()}</td></tr>
 * </table>
 *
 * <h2>Decoding (tag → Java object)</h2>
 * NULL → {@code null}, BOOLEAN → {@link Boolean}, BYTE → {@link Byte}, SHORT → {@link Short}, INT →
 * {@link Integer}, LONG → {@link Long}, FLOAT → {@link Float}, DOUBLE → {@link Double}, DECIMAL →
 * {@link BigDecimal}, STRING → {@link String}, BYTES → {@code byte[]}, DATE → {@link LocalDate}, TIME →
 * {@link LocalTime}, TIMESTAMP → {@link LocalDateTime}, TIMESTAMP_TZ → {@link OffsetDateTime}, TIME_TZ →
 * {@link OffsetTime}, TYPED_NULL → {@link TypedNull}. The driver performs the JDBC conversions
 * ({@code getInt} on a DECIMAL, {@code getTimestamp} on a DATE, …) on top of these.
 */
public final class Values {

    private Values() {
    }

    // ---------------------------------------------------------------- encode

    /**
     * Encodes {@code value} as a tagged {@code Value}, choosing the tag from its runtime type.
     *
     * @param out   destination
     * @param value Java object or {@code null}
     */
    public static void encode(ProtocolOutput out, Object value) {
        switch (value) {
            case null -> out.writeU8(ValueTag.NULL.code());
            case TypedNull tn -> out.writeU8(ValueTag.TYPED_NULL.code()).writeI32(tn.jdbcType());
            case Boolean b -> out.writeU8(ValueTag.BOOLEAN.code()).writeBool(b);
            case Byte b -> out.writeU8(ValueTag.BYTE.code()).writeI8(b);
            case Short s -> out.writeU8(ValueTag.SHORT.code()).writeI16(s);
            case Integer i -> out.writeU8(ValueTag.INT.code()).writeI32(i);
            case Long l -> out.writeU8(ValueTag.LONG.code()).writeI64(l);
            case Float f -> out.writeU8(ValueTag.FLOAT.code()).writeF32(f);
            case Double d -> out.writeU8(ValueTag.DOUBLE.code()).writeF64(d);
            case BigDecimal bd -> encodeDecimal(out, bd);
            case BigInteger bi -> encodeDecimal(out, new BigDecimal(bi));
            case String s -> encodeString(out, s);
            case byte[] b -> out.writeU8(ValueTag.BYTES.code()).writeBytes(b);
            // java.sql.Timestamp/Date/Time extend java.util.Date: test them first.
            case java.sql.Timestamp ts -> encodeTimestamp(out, ts.toLocalDateTime());
            case java.sql.Date d -> encodeDate(out, d.toLocalDate());
            case java.sql.Time t -> encodeTime(out, t.toLocalTime());
            case java.util.Date d -> encodeTimestamp(out, LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
            case LocalDate d -> encodeDate(out, d);
            case LocalTime t -> encodeTime(out, t);
            case LocalDateTime ldt -> encodeTimestamp(out, ldt);
            case OffsetDateTime odt -> encodeTimestampTz(out, odt);
            case ZonedDateTime zdt -> encodeTimestampTz(out, zdt.toOffsetDateTime());
            case Instant i -> encodeTimestampTz(out, i.atOffset(ZoneOffset.UTC));
            case OffsetTime ot -> encodeTimeTz(out, ot);
            case Calendar c -> encodeTimestampTz(out, calendarToOffsetDateTime(c));
            case Character c -> encodeString(out, c.toString());
            case UUID u -> encodeString(out, u.toString());
            case CharSequence cs -> encodeString(out, cs.toString());
            case Number n -> encodeOtherNumber(out, n);
            default -> encodeString(out, value.toString());
        }
    }

    private static void encodeDecimal(ProtocolOutput out, BigDecimal bd) {
        out.writeU8(ValueTag.DECIMAL.code()).writeString(bd.toPlainString());
    }

    private static void encodeString(ProtocolOutput out, String s) {
        out.writeU8(ValueTag.STRING.code()).writeString(s);
    }

    private static void encodeDate(ProtocolOutput out, LocalDate d) {
        out.writeU8(ValueTag.DATE.code()).writeI64(d.toEpochDay());
    }

    private static void encodeTime(ProtocolOutput out, LocalTime t) {
        out.writeU8(ValueTag.TIME.code()).writeI64(t.toNanoOfDay());
    }

    private static void encodeTimestamp(ProtocolOutput out, LocalDateTime ldt) {
        out.writeU8(ValueTag.TIMESTAMP.code())
                .writeI64(ldt.toEpochSecond(ZoneOffset.UTC))
                .writeI32(ldt.getNano());
    }

    private static void encodeTimestampTz(ProtocolOutput out, OffsetDateTime odt) {
        out.writeU8(ValueTag.TIMESTAMP_TZ.code())
                .writeI64(odt.toEpochSecond())
                .writeI32(odt.getNano())
                .writeI32(odt.getOffset().getTotalSeconds());
    }

    private static void encodeTimeTz(ProtocolOutput out, OffsetTime ot) {
        out.writeU8(ValueTag.TIME_TZ.code())
                .writeI64(ot.toLocalTime().toNanoOfDay())
                .writeI32(ot.getOffset().getTotalSeconds());
    }

    private static void encodeOtherNumber(ProtocolOutput out, Number n) {
        // AtomicInteger, LongAdder, custom Number subclasses: keep full precision when the textual form
        // is a valid decimal, otherwise fall back to STRING (e.g. "NaN").
        String text = n.toString();
        try {
            encodeDecimal(out, new BigDecimal(text));
        } catch (NumberFormatException e) {
            encodeString(out, text);
        }
    }

    private static OffsetDateTime calendarToOffsetDateTime(Calendar c) {
        Instant instant = c.toInstant();
        ZoneOffset offset = c.getTimeZone().toZoneId().getRules().getOffset(instant);
        return instant.atOffset(offset);
    }

    // ---------------------------------------------------------------- decode

    /**
     * Decodes one tagged {@code Value} into the Java object listed in the class documentation.
     *
     * @param in source positioned at the tag byte
     * @return the Java object or {@code null} for tag NULL
     * @throws ProtocolException if the tag is unknown or the data malformed
     */
    public static Object decode(ProtocolInput in) throws ProtocolException {
        ValueTag tag = ValueTag.fromCode(in.readU8());
        return decodeData(in, tag);
    }

    /**
     * Decodes the data part of a {@code Value} whose tag has already been read.
     *
     * @param in  source positioned after the tag byte
     * @param tag the tag
     * @return the Java object or {@code null} for tag NULL
     * @throws ProtocolException if the data is malformed
     */
    public static Object decodeData(ProtocolInput in, ValueTag tag) throws ProtocolException {
        try {
            return switch (tag) {
                case NULL -> null;
                case BOOLEAN -> in.readBool();
                case BYTE -> in.readI8();
                case SHORT -> in.readI16();
                case INT -> in.readI32();
                case LONG -> in.readI64();
                case FLOAT -> in.readF32();
                case DOUBLE -> in.readF64();
                case DECIMAL -> {
                    String text = in.readString();
                    if (text == null) {
                        throw new ProtocolException("DECIMAL value with null text");
                    }
                    yield new BigDecimal(text);
                }
                case STRING -> in.readString();
                case BYTES -> in.readBytes();
                case DATE -> LocalDate.ofEpochDay(in.readI64());
                case TIME -> LocalTime.ofNanoOfDay(in.readI64());
                case TIMESTAMP -> {
                    long sec = in.readI64();
                    int nano = in.readI32();
                    yield LocalDateTime.ofEpochSecond(sec, nano, ZoneOffset.UTC);
                }
                case TIMESTAMP_TZ -> {
                    long sec = in.readI64();
                    int nano = in.readI32();
                    int off = in.readI32();
                    yield OffsetDateTime.ofInstant(Instant.ofEpochSecond(sec, nano), ZoneOffset.ofTotalSeconds(off));
                }
                case TIME_TZ -> {
                    long nanoOfDay = in.readI64();
                    int off = in.readI32();
                    yield OffsetTime.of(LocalTime.ofNanoOfDay(nanoOfDay), ZoneOffset.ofTotalSeconds(off));
                }
                case TYPED_NULL -> new TypedNull(in.readI32());
            };
        } catch (NumberFormatException | DateTimeException | ArithmeticException e) {
            throw new ProtocolException("malformed " + tag + " value: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- server-side helpers

    /**
     * Server-side mapping from a column's {@link java.sql.Types} code to the tag used for its cells.
     *
     * <ul>
     *   <li>{@code NUMERIC, DECIMAL → DECIMAL}</li>
     *   <li>{@code TINYINT, SMALLINT → SHORT} (TINYINT may be unsigned 0..255 on some engines, so it is
     *       widened rather than mapped to BYTE)</li>
     *   <li>{@code INTEGER → INT}, {@code BIGINT → LONG}</li>
     *   <li>{@code REAL → FLOAT}, {@code FLOAT, DOUBLE → DOUBLE}</li>
     *   <li>{@code BOOLEAN, BIT → BOOLEAN}</li>
     *   <li>{@code CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR, CLOB, NCLOB, SQLXML → STRING}</li>
     *   <li>{@code BINARY, VARBINARY, LONGVARBINARY, BLOB → BYTES}</li>
     *   <li>{@code DATE → DATE}, {@code TIME → TIME}, {@code TIME_WITH_TIMEZONE → TIME_TZ},
     *       {@code TIMESTAMP → TIMESTAMP}, {@code TIMESTAMP_WITH_TIMEZONE → TIMESTAMP_TZ}</li>
     *   <li>{@code NULL → NULL}</li>
     *   <li>everything else ({@code OTHER, JAVA_OBJECT, ARRAY, STRUCT, REF, ROWID, DATALINK, DISTINCT,
     *       REF_CURSOR}, vendor codes) → STRING</li>
     * </ul>
     *
     * @param jdbcType a {@link java.sql.Types} constant (vendor-specific codes are accepted)
     * @return the tag the server must use for cells of that column
     */
    public static ValueTag tagForJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.NUMERIC, Types.DECIMAL -> ValueTag.DECIMAL;
            case Types.TINYINT, Types.SMALLINT -> ValueTag.SHORT;
            case Types.INTEGER -> ValueTag.INT;
            case Types.BIGINT -> ValueTag.LONG;
            case Types.REAL -> ValueTag.FLOAT;
            case Types.FLOAT, Types.DOUBLE -> ValueTag.DOUBLE;
            case Types.BOOLEAN, Types.BIT -> ValueTag.BOOLEAN;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML -> ValueTag.STRING;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ValueTag.BYTES;
            case Types.DATE -> ValueTag.DATE;
            case Types.TIME -> ValueTag.TIME;
            case Types.TIME_WITH_TIMEZONE -> ValueTag.TIME_TZ;
            case Types.TIMESTAMP -> ValueTag.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> ValueTag.TIMESTAMP_TZ;
            case Types.NULL -> ValueTag.NULL;
            default -> ValueTag.STRING;
        };
    }

    /**
     * Returns the {@link java.sql.Types} constant a value of the given tag is best described by. Used by
     * the driver for values without column metadata (e.g. OUT parameters).
     *
     * @param tag the tag
     * @return a {@link java.sql.Types} constant
     */
    public static int jdbcTypeForTag(ValueTag tag) {
        return switch (tag) {
            case NULL, TYPED_NULL -> Types.NULL;
            case BOOLEAN -> Types.BOOLEAN;
            case BYTE -> Types.TINYINT;
            case SHORT -> Types.SMALLINT;
            case INT -> Types.INTEGER;
            case LONG -> Types.BIGINT;
            case FLOAT -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case DECIMAL -> Types.NUMERIC;
            case STRING -> Types.VARCHAR;
            case BYTES -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            case TIME_TZ -> Types.TIME_WITH_TIMEZONE;
        };
    }

    /**
     * Server-side convenience: reads column {@code column} of the current row of {@code rs} with the
     * getter matching {@code tag} (as returned by {@link #tagForJdbcType}) and appends it as a tagged
     * {@code Value}. SQL NULL is written as tag NULL. For STRING the physical driver's {@code getString}
     * is used, which covers CLOB, XML, JSON, INTERVAL and unknown types as the specification requires;
     * for BYTES {@code getBytes} is used, which covers BLOB.
     *
     * @param out    destination
     * @param rs     result set positioned on a row
     * @param column 1-based column index
     * @param tag    the tag chosen for the column
     * @throws SQLException propagated from the physical driver
     */
    public static void encodeColumn(ProtocolOutput out, ResultSet rs, int column, ValueTag tag) throws SQLException {
        Object v = switch (tag) {
            case NULL, TYPED_NULL -> null;
            case BOOLEAN -> rs.getBoolean(column);
            case BYTE -> rs.getByte(column);
            case SHORT -> rs.getShort(column);
            case INT -> rs.getInt(column);
            case LONG -> rs.getLong(column);
            case FLOAT -> rs.getFloat(column);
            case DOUBLE -> rs.getDouble(column);
            case DECIMAL -> rs.getBigDecimal(column);
            case STRING -> rs.getString(column);
            case BYTES -> rs.getBytes(column);
            case DATE -> rs.getDate(column);
            case TIME -> rs.getTime(column);
            case TIMESTAMP -> rs.getTimestamp(column);
            case TIMESTAMP_TZ -> getObjectOrFallback(rs, column, OffsetDateTime.class);
            case TIME_TZ -> getObjectOrFallback(rs, column, OffsetTime.class);
        };
        if (v == null || rs.wasNull()) {
            out.writeU8(ValueTag.NULL.code());
        } else {
            encode(out, v);
        }
    }

    private static Object getObjectOrFallback(ResultSet rs, int column, Class<?> type) throws SQLException {
        try {
            return rs.getObject(column, type);
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            // Physical driver does not support java.time for this column: fall back to the legacy getter
            // and let encode() choose the tag from the runtime type (system default zone).
            if (type == OffsetTime.class) {
                java.sql.Time t = rs.getTime(column);
                return t == null ? null : t.toLocalTime().atOffset(ZoneId.systemDefault().getRules().getOffset(Instant.now()));
            }
            java.sql.Timestamp ts = rs.getTimestamp(column);
            return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneId.systemDefault());
        }
    }
}
