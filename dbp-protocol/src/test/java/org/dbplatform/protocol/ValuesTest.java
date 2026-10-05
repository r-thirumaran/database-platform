package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesTest {

    private static Object roundTrip(Object value) throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        Values.encode(out, value);
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        Object decoded = Values.decode(in);
        in.expectEnd();
        return decoded;
    }

    private static ValueTag tagOf(Object value) throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        Values.encode(out, value);
        return ValueTag.fromCode(out.toByteArray()[0]);
    }

    static Stream<Object> identityValues() {
        List<Object> v = new ArrayList<>();
        v.add(Boolean.TRUE);
        v.add(Boolean.FALSE);
        v.addAll(List.of((byte) 0, Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) -1));
        v.addAll(List.of((short) 0, Short.MIN_VALUE, Short.MAX_VALUE, (short) -1));
        v.addAll(List.of(0, Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 42));
        v.addAll(List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE, -1L));
        v.addAll(List.of(0f, -0f, Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN, Float.NEGATIVE_INFINITY, 1.5f));
        v.addAll(List.of(0d, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, -2.25d));
        v.addAll(List.of(BigDecimal.ZERO, new BigDecimal("-123456789012345678901234567890.123456789"),
                new BigDecimal("0.000000000000000001"), new BigDecimal("1E+3").setScale(0),
                BigDecimal.valueOf(Long.MIN_VALUE)));
        v.addAll(List.of("", "ascii", "üñí©ødé 日本語 😀", "a\u0000b", "x".repeat(10_000)));
        v.add(new byte[0]);
        v.add(new byte[] {Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE});
        v.addAll(List.of(LocalDate.EPOCH, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), LocalDate.of(1969, 12, 31),
                LocalDate.MIN, LocalDate.MAX));
        v.addAll(List.of(LocalTime.MIDNIGHT, LocalTime.MAX, LocalTime.of(13, 14, 15, 123456789)));
        v.addAll(List.of(LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(1900, 2, 28, 23, 59, 59, 999_999_999),
                LocalDateTime.of(2038, 1, 19, 3, 14, 8), LocalDateTime.of(-500, 6, 15, 12, 0)));
        v.addAll(List.of(OffsetDateTime.of(2024, 2, 29, 12, 30, 45, 1, ZoneOffset.ofHoursMinutes(5, 30)),
                OffsetDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999, ZoneOffset.ofHours(-12)),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.MAX)));
        v.addAll(List.of(OffsetTime.of(23, 59, 59, 999_999_999, ZoneOffset.ofHours(14)),
                OffsetTime.of(0, 0, 0, 0, ZoneOffset.ofHoursMinutesSeconds(-3, -30, -15))));
        v.addAll(List.of(new TypedNull(Types.VARCHAR), new TypedNull(Types.OTHER), new TypedNull(-9999)));
        return v.stream();
    }

    @ParameterizedTest
    @MethodSource("identityValues")
    void roundTripPreservesValueAndType(Object value) throws ProtocolException {
        Object decoded = roundTrip(value);
        assertThat(decoded).isExactlyInstanceOf(value.getClass());
        if (value instanceof byte[] b) {
            assertThat((byte[]) decoded).isEqualTo(b);
        } else if (value instanceof Float f && f.isNaN()) {
            assertThat(((Float) decoded).isNaN()).isTrue();
        } else if (value instanceof Double d && d.isNaN()) {
            assertThat(((Double) decoded).isNaN()).isTrue();
        } else if (value instanceof BigDecimal bd) {
            // tag DECIMAL carries toPlainString(): compare numerically and by plain string
            assertThat((BigDecimal) decoded).isEqualByComparingTo(bd);
            assertThat(((BigDecimal) decoded).toPlainString()).isEqualTo(bd.toPlainString());
        } else {
            assertThat(decoded).isEqualTo(value);
        }
    }

    @Test
    void nullRoundTripsAsSingleByte() throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        Values.encode(out, null);
        assertThat(out.toByteArray()).containsExactly(0);
        assertThat(roundTrip(null)).isNull();
    }

    @Test
    void tagsFollowRuntimeType() throws ProtocolException {
        assertThat(tagOf(null)).isEqualTo(ValueTag.NULL);
        assertThat(tagOf(true)).isEqualTo(ValueTag.BOOLEAN);
        assertThat(tagOf((byte) 1)).isEqualTo(ValueTag.BYTE);
        assertThat(tagOf((short) 1)).isEqualTo(ValueTag.SHORT);
        assertThat(tagOf(1)).isEqualTo(ValueTag.INT);
        assertThat(tagOf(1L)).isEqualTo(ValueTag.LONG);
        assertThat(tagOf(1f)).isEqualTo(ValueTag.FLOAT);
        assertThat(tagOf(1d)).isEqualTo(ValueTag.DOUBLE);
        assertThat(tagOf(BigDecimal.ONE)).isEqualTo(ValueTag.DECIMAL);
        assertThat(tagOf(BigInteger.TEN)).isEqualTo(ValueTag.DECIMAL);
        assertThat(tagOf("s")).isEqualTo(ValueTag.STRING);
        assertThat(tagOf(new byte[1])).isEqualTo(ValueTag.BYTES);
        assertThat(tagOf(LocalDate.EPOCH)).isEqualTo(ValueTag.DATE);
        assertThat(tagOf(LocalTime.NOON)).isEqualTo(ValueTag.TIME);
        assertThat(tagOf(LocalDateTime.MIN)).isEqualTo(ValueTag.TIMESTAMP);
        assertThat(tagOf(OffsetDateTime.now())).isEqualTo(ValueTag.TIMESTAMP_TZ);
        assertThat(tagOf(OffsetTime.now())).isEqualTo(ValueTag.TIME_TZ);
        assertThat(tagOf(new TypedNull(1))).isEqualTo(ValueTag.TYPED_NULL);
    }

    @Test
    void wireLayoutMatchesSpecification() throws ProtocolException {
        // TIMESTAMP: tag 13, i64 epochSecond of the LocalDateTime as if UTC, i32 nano
        LocalDateTime ldt = LocalDateTime.of(2001, 9, 9, 1, 46, 40, 123);
        ProtocolOutput out = new ProtocolOutput();
        Values.encode(out, ldt);
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(13);
        assertThat(in.readI64()).isEqualTo(1_000_000_000L);
        assertThat(in.readI32()).isEqualTo(123);
        in.expectEnd();

        // TIMESTAMP_TZ: tag 14, i64 epochSecond (absolute instant), i32 nano, i32 offsetSeconds
        OffsetDateTime odt = OffsetDateTime.of(2001, 9, 9, 3, 46, 40, 7, ZoneOffset.ofHours(2));
        out = new ProtocolOutput();
        Values.encode(out, odt);
        in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(14);
        assertThat(in.readI64()).isEqualTo(1_000_000_000L);
        assertThat(in.readI32()).isEqualTo(7);
        assertThat(in.readI32()).isEqualTo(7200);
        in.expectEnd();

        // DATE: tag 11, i64 epochDay ; TIME: tag 12, i64 nanoOfDay ; TIME_TZ: tag 15, i64 nanoOfDay, i32 offset
        out = new ProtocolOutput();
        Values.encode(out, LocalDate.of(1970, 1, 11));
        Values.encode(out, LocalTime.of(0, 0, 1));
        Values.encode(out, OffsetTime.of(0, 0, 2, 0, ZoneOffset.ofHours(-1)));
        in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(11);
        assertThat(in.readI64()).isEqualTo(10);
        assertThat(in.readU8()).isEqualTo(12);
        assertThat(in.readI64()).isEqualTo(1_000_000_000L);
        assertThat(in.readU8()).isEqualTo(15);
        assertThat(in.readI64()).isEqualTo(2_000_000_000L);
        assertThat(in.readI32()).isEqualTo(-3600);
        in.expectEnd();

        // DECIMAL is a plain string (no exponent); TYPED_NULL carries the jdbc type
        out = new ProtocolOutput();
        Values.encode(out, new BigDecimal("1E+5"));
        Values.encode(out, new TypedNull(Types.NUMERIC));
        in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(8);
        assertThat(in.readString()).isEqualTo("100000");
        assertThat(in.readU8()).isEqualTo(16);
        assertThat(in.readI32()).isEqualTo(Types.NUMERIC);
        in.expectEnd();
    }

    @Test
    void legacyJdbcAndUtilTypesAreMapped() throws ProtocolException {
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf("2020-05-17 10:11:12.123456789");
        assertThat(roundTrip(ts)).isEqualTo(ts.toLocalDateTime());
        java.sql.Date d = java.sql.Date.valueOf("2020-05-17");
        assertThat(roundTrip(d)).isEqualTo(LocalDate.of(2020, 5, 17));
        java.sql.Time t = java.sql.Time.valueOf("10:11:12");
        assertThat(roundTrip(t)).isEqualTo(LocalTime.of(10, 11, 12));

        java.util.Date utilDate = new java.util.Date(1_000_000_000_123L);
        assertThat(roundTrip(utilDate))
                .isEqualTo(LocalDateTime.ofInstant(utilDate.toInstant(), ZoneId.systemDefault()));

        Instant instant = Instant.ofEpochSecond(1_000_000_000L, 5);
        assertThat(roundTrip(instant)).isEqualTo(instant.atOffset(ZoneOffset.UTC));

        ZonedDateTime zdt = ZonedDateTime.of(2021, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"));
        assertThat(roundTrip(zdt)).isEqualTo(zdt.toOffsetDateTime());

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTimeInMillis(1_000_000_000_000L);
        Object decodedCal = roundTrip(cal);
        assertThat(decodedCal).isInstanceOf(OffsetDateTime.class);
        assertThat(((OffsetDateTime) decodedCal).toInstant()).isEqualTo(cal.toInstant());
        assertThat(((OffsetDateTime) decodedCal).getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
    }

    @Test
    void otherObjectsFallBackToString() throws ProtocolException {
        UUID uuid = UUID.randomUUID();
        assertThat(roundTrip(uuid)).isEqualTo(uuid.toString());
        assertThat(tagOf(uuid)).isEqualTo(ValueTag.STRING);
        assertThat(roundTrip('x')).isEqualTo("x");
        assertThat(roundTrip(new StringBuilder("sb"))).isEqualTo("sb");
        assertThat(roundTrip(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)))
                .isEqualTo(new BigDecimal("92233720368547758070"));
        assertThat(roundTrip(new AtomicInteger(-7))).isEqualTo(new BigDecimal("-7"));
        assertThat(roundTrip(new AtomicLong(Long.MIN_VALUE))).isEqualTo(BigDecimal.valueOf(Long.MIN_VALUE));
        assertThat(roundTrip(Thread.State.RUNNABLE)).isEqualTo("RUNNABLE");
        assertThat(roundTrip(Map.of("k", "v"))).isEqualTo("{k=v}");
        assertThat(roundTrip(List.of(1, 2))).isEqualTo("[1, 2]");
    }

    @Test
    void malformedValueDataIsReportedAsProtocolException() {
        byte[] badDecimal = new ProtocolOutput().writeU8(8).writeString("not a number").toByteArray();
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(badDecimal))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("DECIMAL");
        byte[] nullDecimal = new ProtocolOutput().writeU8(8).writeString(null).toByteArray();
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(nullDecimal))).isInstanceOf(ProtocolException.class);
        byte[] badTime = new ProtocolOutput().writeU8(12).writeI64(-1).toByteArray();
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(badTime))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("TIME");
        byte[] badOffset = new ProtocolOutput().writeU8(14).writeI64(0).writeI32(0).writeI32(99_999_999).toByteArray();
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(badOffset))).isInstanceOf(ProtocolException.class);
        byte[] badTag = {17};
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(badTag))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unknown value tag");
        byte[] truncated = {5, 0, 0};
        assertThatThrownBy(() -> Values.decode(new ProtocolInput(truncated))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void tagForJdbcTypeCoversEveryJavaSqlTypesConstant() throws Exception {
        Map<String, ValueTag> expected = Map.ofEntries(
                Map.entry("BIT", ValueTag.BOOLEAN), Map.entry("BOOLEAN", ValueTag.BOOLEAN),
                Map.entry("TINYINT", ValueTag.SHORT), Map.entry("SMALLINT", ValueTag.SHORT),
                Map.entry("INTEGER", ValueTag.INT), Map.entry("BIGINT", ValueTag.LONG),
                Map.entry("REAL", ValueTag.FLOAT), Map.entry("FLOAT", ValueTag.DOUBLE), Map.entry("DOUBLE", ValueTag.DOUBLE),
                Map.entry("NUMERIC", ValueTag.DECIMAL), Map.entry("DECIMAL", ValueTag.DECIMAL),
                Map.entry("CHAR", ValueTag.STRING), Map.entry("VARCHAR", ValueTag.STRING),
                Map.entry("LONGVARCHAR", ValueTag.STRING), Map.entry("NCHAR", ValueTag.STRING),
                Map.entry("NVARCHAR", ValueTag.STRING), Map.entry("LONGNVARCHAR", ValueTag.STRING),
                Map.entry("CLOB", ValueTag.STRING), Map.entry("NCLOB", ValueTag.STRING), Map.entry("SQLXML", ValueTag.STRING),
                Map.entry("BINARY", ValueTag.BYTES), Map.entry("VARBINARY", ValueTag.BYTES),
                Map.entry("LONGVARBINARY", ValueTag.BYTES), Map.entry("BLOB", ValueTag.BYTES),
                Map.entry("DATE", ValueTag.DATE), Map.entry("TIME", ValueTag.TIME),
                Map.entry("TIME_WITH_TIMEZONE", ValueTag.TIME_TZ), Map.entry("TIMESTAMP", ValueTag.TIMESTAMP),
                Map.entry("TIMESTAMP_WITH_TIMEZONE", ValueTag.TIMESTAMP_TZ),
                Map.entry("NULL", ValueTag.NULL),
                Map.entry("OTHER", ValueTag.STRING), Map.entry("JAVA_OBJECT", ValueTag.STRING),
                Map.entry("DISTINCT", ValueTag.STRING), Map.entry("STRUCT", ValueTag.STRING),
                Map.entry("ARRAY", ValueTag.STRING), Map.entry("REF", ValueTag.STRING),
                Map.entry("DATALINK", ValueTag.STRING), Map.entry("ROWID", ValueTag.STRING),
                Map.entry("REF_CURSOR", ValueTag.STRING));

        int checked = 0;
        for (Field f : Types.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int code = f.getInt(null);
            assertThat(expected).as("test table is missing java.sql.Types.%s", f.getName()).containsKey(f.getName());
            assertThat(Values.tagForJdbcType(code)).as("java.sql.Types.%s", f.getName()).isEqualTo(expected.get(f.getName()));
            checked++;
        }
        assertThat(checked).isEqualTo(expected.size());
        // vendor-specific codes fall back to STRING
        assertThat(Values.tagForJdbcType(-101)).isEqualTo(ValueTag.STRING);
        assertThat(Values.tagForJdbcType(100_000)).isEqualTo(ValueTag.STRING);
    }

    @Test
    void jdbcTypeForTagIsConsistentWithTagForJdbcType() {
        for (ValueTag tag : ValueTag.values()) {
            int jdbc = Values.jdbcTypeForTag(tag);
            ValueTag back = Values.tagForJdbcType(jdbc);
            if (tag == ValueTag.BYTE) {
                assertThat(back).isEqualTo(ValueTag.SHORT); // TINYINT is widened on purpose
            } else if (tag == ValueTag.TYPED_NULL) {
                assertThat(back).isEqualTo(ValueTag.NULL);
            } else {
                assertThat(back).as("tag %s -> %d -> %s", tag, jdbc, back).isEqualTo(tag);
            }
        }
    }

    @Test
    void encodeColumnUsesTheGetterMatchingTheTagAndHonoursWasNull() throws Exception {
        Map<Integer, Object> cells = new java.util.HashMap<>();
        cells.put(1, 42);
        cells.put(2, "text");
        cells.put(3, new BigDecimal("1.50"));
        cells.put(4, null);
        cells.put(5, java.sql.Timestamp.valueOf("2020-01-02 03:04:05"));
        cells.put(6, new byte[] {1, 2});
        cells.put(7, OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(1)));
        cells.put(8, true);
        cells.put(9, 0L);
        ResultSet rs = fakeResultSet(cells);
        ProtocolOutput out = new ProtocolOutput();
        Values.encodeColumn(out, rs, 1, ValueTag.INT);
        Values.encodeColumn(out, rs, 2, ValueTag.STRING);
        Values.encodeColumn(out, rs, 3, ValueTag.DECIMAL);
        Values.encodeColumn(out, rs, 4, ValueTag.STRING);       // SQL NULL via null return
        Values.encodeColumn(out, rs, 5, ValueTag.TIMESTAMP);
        Values.encodeColumn(out, rs, 6, ValueTag.BYTES);
        Values.encodeColumn(out, rs, 7, ValueTag.TIMESTAMP_TZ);
        Values.encodeColumn(out, rs, 8, ValueTag.BOOLEAN);
        Values.encodeColumn(out, rs, 9, ValueTag.LONG);         // primitive getter returned 0 but wasNull() is true
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readValue()).isEqualTo(42);
        assertThat(in.readValue()).isEqualTo("text");
        assertThat(in.readValue()).isEqualTo(new BigDecimal("1.50"));
        assertThat(in.readValue()).isNull();
        assertThat(in.readValue()).isEqualTo(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        assertThat((byte[]) in.readValue()).containsExactly(1, 2);
        assertThat(in.readValue()).isEqualTo(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(1)));
        assertThat(in.readValue()).isEqualTo(true);
        assertThat(in.readValue()).isNull();
        in.expectEnd();
    }

    /** Minimal ResultSet stand-in: getXxx(column) returns the map value; wasNull() reflects the last read. */
    private static ResultSet fakeResultSet(Map<Integer, Object> columns) {
        final boolean[] wasNull = {false};
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("wasNull")) {
                return wasNull[0];
            }
            if (name.startsWith("get") && args != null && args.length >= 1 && args[0] instanceof Integer col) {
                Object v = columns.get(col);
                // column 9 simulates a primitive getter on a NULL cell
                wasNull[0] = v == null || col == 9;
                if (v == null) {
                    return method.getReturnType().isPrimitive() ? defaultPrimitive(method.getReturnType()) : null;
                }
                return v;
            }
            throw new UnsupportedOperationException(name);
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, h);
    }

    private static Object defaultPrimitive(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        return 0d;
    }

    @Test
    void largeStringValueRoundTrips() throws ProtocolException {
        String big = "é".repeat(1_000_000);
        assertThat(roundTrip(big)).isEqualTo(big);
        byte[] bigBytes = new byte[3_000_000];
        Arrays.fill(bigBytes, (byte) 7);
        assertThat((byte[]) roundTrip(bigBytes)).isEqualTo(bigBytes);
    }
}
