package org.dbplatform.jdbc;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
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
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Table-driven conversion test over the {@code SELECT TYPES} row of the fake gateway. */
class ResultSetConversionTest extends GatewayTest {

    @FunctionalInterface
    interface Accessor {
        Object get(ResultSet rs, int column) throws SQLException;
    }

    record Case(String column, String accessor, Accessor get, Object expected) {
    }

    record Failing(String column, String accessor, Accessor get, String sqlState) {
    }

    private static final ZoneId SYSTEM = ZoneId.systemDefault();
    private static final LocalDateTime TS = LocalDateTime.of(2024, 1, 15, 10, 20, 30, 500_000_000);
    private static final OffsetDateTime TSTZ = OffsetDateTime.of(2024, 1, 15, 10, 20, 30, 0, ZoneOffset.ofHours(2));
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private static List<Case> cases() {
        List<Case> l = new ArrayList<>();
        // booleans
        l.add(new Case("C_BOOL", "getBoolean", ResultSet::getBoolean, true));
        l.add(new Case("C_BOOL", "getString", ResultSet::getString, "true"));
        l.add(new Case("C_BOOL", "getInt", ResultSet::getInt, 1));
        l.add(new Case("C_BOOL", "getObject", ResultSet::getObject, Boolean.TRUE));
        l.add(new Case("C_BOOL", "getObject(Integer)", (rs, i) -> rs.getObject(i, Integer.class), 1));
        l.add(new Case("C_BOOL", "getBigDecimal", ResultSet::getBigDecimal, BigDecimal.ONE));
        // small integers
        l.add(new Case("C_BYTE", "getByte", ResultSet::getByte, (byte) 7));
        l.add(new Case("C_BYTE", "getInt", ResultSet::getInt, 7));
        l.add(new Case("C_BYTE", "getString", ResultSet::getString, "7"));
        l.add(new Case("C_BYTE", "getObject", ResultSet::getObject, (byte) 7));
        l.add(new Case("C_SHORT", "getShort", ResultSet::getShort, (short) 300));
        l.add(new Case("C_SHORT", "getLong", ResultSet::getLong, 300L));
        l.add(new Case("C_SHORT", "getObject(Long)", (rs, i) -> rs.getObject(i, Long.class), 300L));
        // int
        l.add(new Case("C_INT", "getInt", ResultSet::getInt, 42));
        l.add(new Case("C_INT", "getString", ResultSet::getString, "42"));
        l.add(new Case("C_INT", "getLong", ResultSet::getLong, 42L));
        l.add(new Case("C_INT", "getDouble", ResultSet::getDouble, 42d));
        l.add(new Case("C_INT", "getFloat", ResultSet::getFloat, 42f));
        l.add(new Case("C_INT", "getShort", ResultSet::getShort, (short) 42));
        l.add(new Case("C_INT", "getByte", ResultSet::getByte, (byte) 42));
        l.add(new Case("C_INT", "getBigDecimal", ResultSet::getBigDecimal, BigDecimal.valueOf(42)));
        l.add(new Case("C_INT", "getBoolean", ResultSet::getBoolean, true));
        l.add(new Case("C_INT", "getObject", ResultSet::getObject, 42));
        l.add(new Case("C_INT", "getObject(String)", (rs, i) -> rs.getObject(i, String.class), "42"));
        l.add(new Case("C_INT", "getObject(BigInteger)", (rs, i) -> rs.getObject(i, BigInteger.class), BigInteger.valueOf(42)));
        l.add(new Case("C_INT", "getObject(Number)", (rs, i) -> rs.getObject(i, Number.class), 42));
        l.add(new Case("C_INT", "getObject(Object)", (rs, i) -> rs.getObject(i, Object.class), 42));
        // long
        l.add(new Case("C_LONG", "getLong", ResultSet::getLong, 9_000_000_000L));
        l.add(new Case("C_LONG", "getString", ResultSet::getString, "9000000000"));
        l.add(new Case("C_LONG", "getDouble", ResultSet::getDouble, 9e9));
        l.add(new Case("C_LONG", "getObject", ResultSet::getObject, 9_000_000_000L));
        // floating point
        l.add(new Case("C_FLOAT", "getFloat", ResultSet::getFloat, 1.5f));
        l.add(new Case("C_FLOAT", "getDouble", ResultSet::getDouble, 1.5d));
        l.add(new Case("C_FLOAT", "getInt", ResultSet::getInt, 1));
        l.add(new Case("C_FLOAT", "getString", ResultSet::getString, "1.5"));
        l.add(new Case("C_FLOAT", "getBigDecimal", ResultSet::getBigDecimal, new BigDecimal("1.5")));
        l.add(new Case("C_DOUBLE", "getDouble", ResultSet::getDouble, 2.25d));
        l.add(new Case("C_DOUBLE", "getBigDecimal", ResultSet::getBigDecimal, new BigDecimal("2.25")));
        l.add(new Case("C_DOUBLE", "getFloat", ResultSet::getFloat, 2.25f));
        l.add(new Case("C_DOUBLE", "getLong", ResultSet::getLong, 2L));
        l.add(new Case("C_DOUBLE", "getObject", ResultSet::getObject, 2.25d));
        // decimal
        l.add(new Case("C_DEC", "getBigDecimal", ResultSet::getBigDecimal, new BigDecimal("12.50")));
        l.add(new Case("C_DEC", "getBigDecimal(1)", (rs, i) -> rs.getBigDecimal(i, 1), new BigDecimal("12.5")));
        l.add(new Case("C_DEC", "getInt", ResultSet::getInt, 12));
        l.add(new Case("C_DEC", "getLong", ResultSet::getLong, 12L));
        l.add(new Case("C_DEC", "getShort", ResultSet::getShort, (short) 12));
        l.add(new Case("C_DEC", "getDouble", ResultSet::getDouble, 12.5d));
        l.add(new Case("C_DEC", "getString", ResultSet::getString, "12.50"));
        l.add(new Case("C_DEC", "getBoolean", ResultSet::getBoolean, true));
        l.add(new Case("C_DEC", "getObject", ResultSet::getObject, new BigDecimal("12.50")));
        l.add(new Case("C_DEC", "getObject(Double)", (rs, i) -> rs.getObject(i, Double.class), 12.5d));
        // string
        l.add(new Case("C_STR", "getString", ResultSet::getString, "hello"));
        l.add(new Case("C_STR", "getNString", ResultSet::getNString, "hello"));
        l.add(new Case("C_STR", "getObject", ResultSet::getObject, "hello"));
        l.add(new Case("C_STR", "getBytes", ResultSet::getBytes, "hello".getBytes(StandardCharsets.UTF_8)));
        l.add(new Case("C_STR", "getCharacterStream", (rs, i) -> readAll(rs.getCharacterStream(i)), "hello"));
        l.add(new Case("C_STR", "getNCharacterStream", (rs, i) -> readAll(rs.getNCharacterStream(i)), "hello"));
        l.add(new Case("C_STR", "getAsciiStream", (rs, i) -> readAll(rs.getAsciiStream(i)), "hello".getBytes(StandardCharsets.US_ASCII)));
        l.add(new Case("C_STR", "getClob", (rs, i) -> rs.getClob(i).getSubString(1, 5), "hello"));
        l.add(new Case("C_STR", "getNClob", (rs, i) -> rs.getNClob(i).length(), 5L));
        l.add(new Case("C_STR", "getObject(Clob)", (rs, i) -> rs.getObject(i, java.sql.Clob.class).length(), 5L));
        // bytes
        l.add(new Case("C_BYTES", "getBytes", ResultSet::getBytes, new byte[] {1, 2, 3}));
        l.add(new Case("C_BYTES", "getString", ResultSet::getString, "010203"));
        l.add(new Case("C_BYTES", "getObject", ResultSet::getObject, new byte[] {1, 2, 3}));
        l.add(new Case("C_BYTES", "getBlob", (rs, i) -> rs.getBlob(i).getBytes(1, 3), new byte[] {1, 2, 3}));
        l.add(new Case("C_BYTES", "getBinaryStream", (rs, i) -> readAll(rs.getBinaryStream(i)), new byte[] {1, 2, 3}));
        l.add(new Case("C_BYTES", "getObject(byte[])", (rs, i) -> rs.getObject(i, byte[].class), new byte[] {1, 2, 3}));
        // date
        l.add(new Case("C_DATE", "getDate", ResultSet::getDate, Date.valueOf("2024-01-15")));
        l.add(new Case("C_DATE", "getTimestamp", ResultSet::getTimestamp, Timestamp.valueOf("2024-01-15 00:00:00")));
        l.add(new Case("C_DATE", "getString", ResultSet::getString, "2024-01-15"));
        l.add(new Case("C_DATE", "getObject", ResultSet::getObject, Date.valueOf("2024-01-15")));
        l.add(new Case("C_DATE", "getObject(LocalDate)", (rs, i) -> rs.getObject(i, LocalDate.class), LocalDate.of(2024, 1, 15)));
        l.add(new Case("C_DATE", "getObject(LocalDateTime)", (rs, i) -> rs.getObject(i, LocalDateTime.class), LocalDate.of(2024, 1, 15).atStartOfDay()));
        l.add(new Case("C_DATE", "getDate(UTC)", (rs, i) -> rs.getDate(i, UTC).getTime(),
                LocalDate.of(2024, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()));
        l.add(new Case("C_DATE", "getObject(java.util.Date)", (rs, i) -> rs.getObject(i, java.util.Date.class),
                new java.util.Date(Date.valueOf("2024-01-15").getTime())));
        // time
        l.add(new Case("C_TIME", "getTime", ResultSet::getTime, Time.valueOf("10:20:30")));
        l.add(new Case("C_TIME", "getString", ResultSet::getString, "10:20:30"));
        l.add(new Case("C_TIME", "getObject", ResultSet::getObject, Time.valueOf("10:20:30")));
        l.add(new Case("C_TIME", "getObject(LocalTime)", (rs, i) -> rs.getObject(i, LocalTime.class), LocalTime.of(10, 20, 30)));
        l.add(new Case("C_TIME", "getTimestamp", ResultSet::getTimestamp, Timestamp.valueOf("1970-01-01 10:20:30")));
        l.add(new Case("C_TIME", "getTime(UTC)", (rs, i) -> rs.getTime(i, UTC).getTime(),
                LocalDate.EPOCH.atTime(10, 20, 30).toInstant(ZoneOffset.UTC).toEpochMilli()));
        // timestamp
        l.add(new Case("C_TS", "getTimestamp", ResultSet::getTimestamp, Timestamp.valueOf(TS)));
        l.add(new Case("C_TS", "getDate", ResultSet::getDate, Date.valueOf("2024-01-15")));
        l.add(new Case("C_TS", "getTime.getTime", (rs, i) -> rs.getTime(i).getTime(), Time.valueOf("10:20:30").getTime() + 500));
        l.add(new Case("C_TS", "getString", ResultSet::getString, "2024-01-15 10:20:30.5"));
        l.add(new Case("C_TS", "getObject", ResultSet::getObject, Timestamp.valueOf(TS)));
        l.add(new Case("C_TS", "getObject(LocalDateTime)", (rs, i) -> rs.getObject(i, LocalDateTime.class), TS));
        l.add(new Case("C_TS", "getObject(LocalDate)", (rs, i) -> rs.getObject(i, LocalDate.class), TS.toLocalDate()));
        l.add(new Case("C_TS", "getObject(LocalTime)", (rs, i) -> rs.getObject(i, LocalTime.class), TS.toLocalTime()));
        l.add(new Case("C_TS", "getObject(OffsetDateTime)", (rs, i) -> rs.getObject(i, OffsetDateTime.class), TS.atZone(SYSTEM).toOffsetDateTime()));
        l.add(new Case("C_TS", "getObject(Instant)", (rs, i) -> rs.getObject(i, Instant.class), TS.atZone(SYSTEM).toInstant()));
        l.add(new Case("C_TS", "getTimestamp(UTC)", (rs, i) -> rs.getTimestamp(i, UTC), Timestamp.from(TS.toInstant(ZoneOffset.UTC))));
        // timestamp with time zone
        l.add(new Case("C_TSTZ", "getObject", ResultSet::getObject, TSTZ));
        l.add(new Case("C_TSTZ", "getObject(OffsetDateTime)", (rs, i) -> rs.getObject(i, OffsetDateTime.class), TSTZ));
        l.add(new Case("C_TSTZ", "getObject(ZonedDateTime)", (rs, i) -> rs.getObject(i, ZonedDateTime.class), TSTZ.toZonedDateTime()));
        l.add(new Case("C_TSTZ", "getObject(Instant)", (rs, i) -> rs.getObject(i, Instant.class), TSTZ.toInstant()));
        l.add(new Case("C_TSTZ", "getTimestamp", ResultSet::getTimestamp, Timestamp.from(TSTZ.toInstant())));
        l.add(new Case("C_TSTZ", "getTimestamp(UTC)", (rs, i) -> rs.getTimestamp(i, UTC), Timestamp.from(TSTZ.toInstant())));
        l.add(new Case("C_TSTZ", "getString", ResultSet::getString, "2024-01-15 10:20:30+02:00"));
        l.add(new Case("C_TSTZ", "getObject(LocalDateTime)", (rs, i) -> rs.getObject(i, LocalDateTime.class), TSTZ.toLocalDateTime()));
        l.add(new Case("C_TSTZ", "getDate(UTC)", (rs, i) -> rs.getDate(i, UTC).getTime(),
                LocalDate.of(2024, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()));
        l.add(new Case("C_TSTZ", "getObject(OffsetTime)", (rs, i) -> rs.getObject(i, OffsetTime.class), TSTZ.toOffsetTime()));
        // time with time zone
        l.add(new Case("C_TIMETZ", "getObject", ResultSet::getObject, OffsetTime.of(10, 20, 30, 0, ZoneOffset.ofHours(2))));
        l.add(new Case("C_TIMETZ", "getString", ResultSet::getString, "10:20:30+02:00"));
        l.add(new Case("C_TIMETZ", "getObject(OffsetTime)", (rs, i) -> rs.getObject(i, OffsetTime.class), OffsetTime.of(10, 20, 30, 0, ZoneOffset.ofHours(2))));
        l.add(new Case("C_TIMETZ", "getObject(LocalTime)", (rs, i) -> rs.getObject(i, LocalTime.class), LocalTime.of(10, 20, 30)));
        l.add(new Case("C_TIMETZ", "getTime(UTC)", (rs, i) -> rs.getTime(i, UTC).getTime(),
                LocalDate.EPOCH.atTime(8, 20, 30).toInstant(ZoneOffset.UTC).toEpochMilli()));
        // null
        l.add(new Case("C_NULL", "getInt", ResultSet::getInt, 0));
        l.add(new Case("C_NULL", "getLong", ResultSet::getLong, 0L));
        l.add(new Case("C_NULL", "getDouble", ResultSet::getDouble, 0d));
        l.add(new Case("C_NULL", "getBoolean", ResultSet::getBoolean, false));
        l.add(new Case("C_NULL", "getString", ResultSet::getString, null));
        l.add(new Case("C_NULL", "getObject", ResultSet::getObject, null));
        l.add(new Case("C_NULL", "getBigDecimal", ResultSet::getBigDecimal, null));
        l.add(new Case("C_NULL", "getBytes", ResultSet::getBytes, null));
        l.add(new Case("C_NULL", "getDate", ResultSet::getDate, null));
        l.add(new Case("C_NULL", "getTimestamp", ResultSet::getTimestamp, null));
        l.add(new Case("C_NULL", "getBlob", ResultSet::getBlob, null));
        l.add(new Case("C_NULL", "getClob", ResultSet::getClob, null));
        l.add(new Case("C_NULL", "getCharacterStream", ResultSet::getCharacterStream, null));
        l.add(new Case("C_NULL", "getObject(Integer)", (rs, i) -> rs.getObject(i, Integer.class), null));
        l.add(new Case("C_NULL", "getObject(LocalDate)", (rs, i) -> rs.getObject(i, LocalDate.class), null));
        // strings holding other things
        l.add(new Case("C_NUMSTR", "getDouble", ResultSet::getDouble, 123.45d));
        l.add(new Case("C_NUMSTR", "getFloat", ResultSet::getFloat, 123.45f));
        l.add(new Case("C_NUMSTR", "getBigDecimal", ResultSet::getBigDecimal, new BigDecimal("123.45")));
        l.add(new Case("C_NUMSTR", "getInt", ResultSet::getInt, 123));
        l.add(new Case("C_NUMSTR", "getLong", ResultSet::getLong, 123L));
        l.add(new Case("C_NUMSTR", "getBoolean", ResultSet::getBoolean, true));
        l.add(new Case("C_BOOLSTR", "getBoolean", ResultSet::getBoolean, true));
        l.add(new Case("C_BOOLSTR", "getObject(Boolean)", (rs, i) -> rs.getObject(i, Boolean.class), true));
        l.add(new Case("C_DATESTR", "getDate", ResultSet::getDate, Date.valueOf("2024-01-15")));
        l.add(new Case("C_DATESTR", "getTimestamp", ResultSet::getTimestamp, Timestamp.valueOf("2024-01-15 00:00:00")));
        l.add(new Case("C_DATESTR", "getObject(LocalDate)", (rs, i) -> rs.getObject(i, LocalDate.class), LocalDate.of(2024, 1, 15)));
        l.add(new Case("C_TSSTR", "getTimestamp", ResultSet::getTimestamp, Timestamp.valueOf("2024-01-15 10:20:30.5")));
        l.add(new Case("C_TSSTR", "getDate", ResultSet::getDate, Date.valueOf("2024-01-15")));
        l.add(new Case("C_TSSTR", "getTime", ResultSet::getTime, new Time(Time.valueOf("10:20:30").getTime() + 500)));
        l.add(new Case("C_TSSTR", "getObject(LocalDateTime)", (rs, i) -> rs.getObject(i, LocalDateTime.class), TS));
        l.add(new Case("C_TSSTR", "getObject(Instant)", (rs, i) -> rs.getObject(i, Instant.class), TS.atZone(SYSTEM).toInstant()));
        l.add(new Case("C_UUID", "getObject(UUID)", (rs, i) -> rs.getObject(i, UUID.class), UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
        l.add(new Case("C_UUID", "getString", ResultSet::getString, "123e4567-e89b-12d3-a456-426614174000"));
        l.add(new Case("C_BIGDEC", "getBigDecimal", ResultSet::getBigDecimal, new BigDecimal("99999999999999999999")));
        l.add(new Case("C_BIGDEC", "getDouble", ResultSet::getDouble, 1e20));
        l.add(new Case("C_BIGDEC", "getObject(BigInteger)", (rs, i) -> rs.getObject(i, BigInteger.class), new BigInteger("99999999999999999999")));
        return l;
    }

    private static List<Failing> failing() {
        List<Failing> l = new ArrayList<>();
        l.add(new Failing("C_SHORT", "getByte", ResultSet::getByte, "22003"));
        l.add(new Failing("C_LONG", "getInt", ResultSet::getInt, "22003"));
        l.add(new Failing("C_BIGDEC", "getLong", ResultSet::getLong, "22003"));
        l.add(new Failing("C_STR", "getInt", ResultSet::getInt, "22018"));
        l.add(new Failing("C_STR", "getDouble", ResultSet::getDouble, "22018"));
        l.add(new Failing("C_STR", "getBoolean", ResultSet::getBoolean, "22018"));
        l.add(new Failing("C_STR", "getDate", ResultSet::getDate, "22018"));
        l.add(new Failing("C_STR", "getTimestamp", ResultSet::getTimestamp, "22018"));
        l.add(new Failing("C_STR", "getObject(UUID)", (rs, i) -> rs.getObject(i, UUID.class), "22018"));
        l.add(new Failing("C_INT", "getBytes", ResultSet::getBytes, "22018"));
        l.add(new Failing("C_INT", "getDate", ResultSet::getDate, "22018"));
        l.add(new Failing("C_DATE", "getInt", ResultSet::getInt, "22018"));
        l.add(new Failing("C_INT", "getObject(Thread)", (rs, i) -> rs.getObject(i, Thread.class), "0A000"));
        return l;
    }

    @Test
    void conversions() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select types")) {
            assertThat(rs.next()).isTrue();
            SoftAssertions soft = new SoftAssertions();
            for (Case t : cases()) {
                int col = rs.findColumn(t.column());
                try {
                    Object actual = t.get().get(rs, col);
                    soft.assertThat(actual).as(t.column() + "." + t.accessor()).isEqualTo(t.expected());
                    soft.assertThat(rs.wasNull()).as(t.column() + "." + t.accessor() + " wasNull").isEqualTo(t.column().equals("C_NULL"));
                } catch (SQLException e) {
                    soft.fail(t.column() + "." + t.accessor() + " threw " + e);
                }
            }
            for (Failing f : failing()) {
                int col = rs.findColumn(f.column());
                try {
                    Object v = f.get().get(rs, col);
                    soft.fail(f.column() + "." + f.accessor() + " should fail with " + f.sqlState() + " but returned " + v);
                } catch (SQLException e) {
                    soft.assertThat(e.getSQLState()).as(f.column() + "." + f.accessor()).isEqualTo(f.sqlState());
                    if ("0A000".equals(f.sqlState())) {
                        soft.assertThat((Throwable) e).isInstanceOf(SQLFeatureNotSupportedException.class);
                    } else {
                        soft.assertThat((Throwable) e).isInstanceOf(SQLDataException.class);
                    }
                }
            }
            soft.assertAll();
        }
    }

    @Test
    void columnLookupAndCursorStateErrors() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select types");
            assertThat(rs.findColumn("c_int")).isEqualTo(4);
            assertThat(rs.findColumn("C_INT")).isEqualTo(4);
            assertThatThrownBy(() -> rs.findColumn("nope")).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42S22"));
            assertThatThrownBy(() -> rs.getInt(1)).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
            rs.next();
            assertThatThrownBy(() -> rs.getInt(0)).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07009"));
            assertThatThrownBy(() -> rs.getInt(99)).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07009"));
            assertThat(rs.getObject("c_int", Integer.class)).isEqualTo(42);
            assertThat(rs.getBigDecimal("c_dec", 0)).isEqualByComparingTo("13");
            assertThat(rs.getObject(4, (java.util.Map<String, Class<?>>) null)).isEqualTo(42);
            assertThatThrownBy(() -> rs.getArray(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.getRef(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.getRowId(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.getSQLXML(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.getURL(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.updateInt(1, 1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(rs::insertRow).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.absolute(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(rs::previous).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(rs::beforeFirst).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> rs.setFetchDirection(ResultSet.FETCH_REVERSE)).isInstanceOf(SQLException.class);
            assertThat(rs.getFetchDirection()).isEqualTo(ResultSet.FETCH_FORWARD);
            assertThat(rs.getHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            assertThat(rs.rowUpdated()).isFalse();
            rs.setFetchSize(5);
            assertThat(rs.getFetchSize()).isEqualTo(5);
            rs.next();
            assertThatThrownBy(() -> rs.getInt(1)).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
            rs.close();
            assertThatThrownBy(rs::getMetaData).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
        }
    }

    @Test
    void lobHelpers() throws Exception {
        DbpBlob blob = new DbpBlob(new byte[] {1, 2, 3, 4});
        assertThat(blob.length()).isEqualTo(4);
        assertThat(blob.getBytes(2, 2)).containsExactly(2, 3);
        assertThat(blob.position(new byte[] {3, 4}, 1)).isEqualTo(3);
        blob.setBytes(5, new byte[] {5});
        assertThat(blob.length()).isEqualTo(5);
        blob.truncate(2);
        assertThat(readAll(blob.getBinaryStream())).containsExactly(1, 2);
        blob.free();
        assertThatThrownBy(blob::length).isInstanceOf(SQLException.class);

        DbpClob clob = new DbpClob("hello world");
        assertThat(clob.length()).isEqualTo(11);
        assertThat(clob.getSubString(7, 5)).isEqualTo("world");
        assertThat(clob.position("world", 1)).isEqualTo(7);
        assertThat(readAll(clob.getCharacterStream(1, 5))).isEqualTo("hello");
        clob.setString(7, "there");
        assertThat(clob.getSubString(1, 11)).isEqualTo("hello there");
        try (java.io.Writer w = clob.setCharacterStream(1)) {
            w.write("HELLO");
        }
        assertThat(clob.getSubString(1, 5)).isEqualTo("HELLO");
        clob.truncate(5);
        assertThat(clob.length()).isEqualTo(5);
        assertThat(clob.toString()).contains("5 chars");
    }

    private static String readAll(Reader r) throws SQLException {
        return Conversions.readFully(r, -1);
    }

    private static byte[] readAll(InputStream in) throws SQLException {
        return Conversions.readFully(in, -1);
    }
}
