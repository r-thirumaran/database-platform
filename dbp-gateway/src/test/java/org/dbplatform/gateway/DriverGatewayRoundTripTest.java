package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.session.LogicalSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-module conformance test: the <em>real</em> {@code dbp-jdbc} driver (loaded through {@link DriverManager}
 * from {@code dbp-jdbc/target/classes}, see the surefire configuration) talks to an in-process gateway backed by H2.
 * Only {@code java.sql} is used here so this module needs no compile-time dependency on the driver; the test skips
 * itself when the driver classes are not on the classpath.
 */
class DriverGatewayRoundTripTest {

    static final String URL = "jdbc:h2:mem:rt;DB_CLOSE_DELAY=-1";
    static final String URL_ORA = "jdbc:h2:mem:rtora;DB_CLOSE_DELAY=-1;MODE=Oracle";
    static GatewayFixture gw;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(driverPresent(), "dbp-jdbc classes are not on the test classpath (run `mvn -pl dbp-jdbc test` first)");
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE types_t (id INT PRIMARY KEY, c_big BIGINT, c_small SMALLINT, c_tiny TINYINT,"
                    + " c_dec DECIMAL(12,3), c_num NUMERIC(20,0), c_dbl DOUBLE PRECISION, c_real REAL, c_bool BOOLEAN,"
                    + " c_str VARCHAR(100), c_clob CLOB, c_bin VARBINARY(100), c_blob BLOB, c_date DATE, c_time TIME(9),"
                    + " c_ts TIMESTAMP(9), c_tstz TIMESTAMP(9) WITH TIME ZONE, c_timetz TIME(9) WITH TIME ZONE,"
                    + " c_uuid UUID, c_null VARCHAR(10))");
            st.execute("CREATE TABLE nums (n INT PRIMARY KEY)");
            st.execute("INSERT INTO nums VALUES (1),(2),(3),(4),(5)");
            st.execute("CREATE TABLE tx_t (id INT PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE keys_t (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE batch_t (id INT PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE ts_t (id INT PRIMARY KEY, ts TIMESTAMP(9), d DATE)");
            st.execute("CREATE TABLE load_t (id INT AUTO_INCREMENT PRIMARY KEY, v INT)");
        }
        try (Connection c = DriverManager.getConnection(URL_ORA, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE ora_t (id NUMBER(10) PRIMARY KEY, amount NUMBER, v VARCHAR2(20))");
            st.execute("INSERT INTO ora_t VALUES (7, 12.5, 'seven')");
        }
        gw = GatewayFixture.start("gw-rt", List.of(
                StaticConfig.DatasourceConfig.of("h2", "H2", URL, "sa", "", "TRANSACTION", 5).withConnectionTimeoutMs(500),
                StaticConfig.DatasourceConfig.of("h2ora", "H2", URL_ORA, "sa", "", "SESSION", 3).withConnectionTimeoutMs(500)));
    }

    @AfterAll
    static void stop() {
        if (gw != null) {
            gw.close();
        }
    }

    static boolean driverPresent() {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            try {
                if (drivers.nextElement().acceptsURL("jdbc:dbp://localhost/x")) {
                    return true;
                }
            } catch (SQLException ignored) {
                // not ours
            }
        }
        return false;
    }

    static String url(String datasource) {
        return "jdbc:dbp://127.0.0.1:" + gw.port() + "/" + datasource;
    }

    static Connection connect(String datasource) throws SQLException {
        return DriverManager.getConnection(url(datasource), "tester", "not-an-api-key");
    }

    /** The gateway-side session of a driver connection (the driver exposes {@code getSessionId()}). */
    static LogicalSession session(Connection c) throws Exception {
        String id = (String) c.getClass().getMethod("getSessionId").invoke(c);
        return gw.gateway.sessions().sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ value matrix

    @Test
    void everyValueTypeRoundTripsThroughTheRealDriver() throws Exception {
        Timestamp ts = Timestamp.valueOf("1969-07-20 20:17:40.123456789");          // negative epoch, nanos
        OffsetDateTime tstz = OffsetDateTime.of(1969, 7, 20, 20, 17, 40, 123456000, ZoneOffset.ofHours(-5));
        OffsetTime timetz = OffsetTime.of(10, 20, 30, 500_000_000, ZoneOffset.ofHoursMinutes(5, 30));
        LocalTime time = LocalTime.of(23, 59, 58, 123_000_000);                      // millis must survive
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] bin = {0, 1, 2, (byte) 0xFF, 127, -128};
        String str = "héllo wörld ✓ 日本語 😀";
        String clob = "c".repeat(10_000);
        try (Connection c = connect("h2")) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO types_t VALUES (" + "?,".repeat(19) + "?)")) {
                ps.setInt(1, 1);
                ps.setLong(2, 9_000_000_000L);
                ps.setShort(3, (short) -7);
                ps.setByte(4, (byte) 100);
                ps.setBigDecimal(5, new BigDecimal("1234.500"));
                ps.setBigDecimal(6, new BigDecimal("99999999999999999999"));
                ps.setDouble(7, 2.5d);
                ps.setFloat(8, 1.5f);
                ps.setBoolean(9, true);
                ps.setString(10, str);
                ps.setCharacterStream(11, new StringReader(clob));
                ps.setBytes(12, bin);
                ps.setBlob(13, new ByteArrayInputStream(bin));
                ps.setDate(14, java.sql.Date.valueOf("1969-12-31"));
                ps.setObject(15, time);
                ps.setTimestamp(16, ts);
                ps.setObject(17, tstz);
                ps.setObject(18, timetz);
                ps.setObject(19, uuid);
                ps.setNull(20, Types.VARCHAR);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM types_t WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(1);
                    assertThat(rs.getString(1)).isEqualTo("1");
                    assertThat(rs.getLong("c_big")).isEqualTo(9_000_000_000L);
                    assertThat(rs.getShort("c_small")).isEqualTo((short) -7);
                    assertThat(rs.getByte("c_tiny")).isEqualTo((byte) 100);
                    assertThat(rs.getBigDecimal("c_dec")).isEqualTo(new BigDecimal("1234.500"));
                    assertThat(rs.getBigDecimal("c_dec").scale()).isEqualTo(3);
                    assertThat(rs.getInt("c_dec")).isEqualTo(1234);
                    assertThat(rs.getLong("c_dec")).isEqualTo(1234L);
                    assertThat(rs.getDouble("c_dec")).isEqualTo(1234.5d);
                    assertThat(rs.getString("c_dec")).isEqualTo("1234.500");
                    assertThat(rs.getBigDecimal("c_num")).isEqualTo(new BigDecimal("99999999999999999999"));
                    assertThatThrownBy(() -> rs.getLong("c_num")).isInstanceOf(SQLDataException.class)
                            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22003"));
                    assertThat(rs.getDouble("c_dbl")).isEqualTo(2.5d);
                    assertThat(rs.getFloat("c_real")).isEqualTo(1.5f);
                    assertThat(rs.getBoolean("c_bool")).isTrue();
                    assertThat(rs.getString("c_bool")).isEqualToIgnoringCase("true");
                    assertThat(rs.getString("c_str")).isEqualTo(str);
                    assertThat(rs.getString("c_clob")).isEqualTo(clob);
                    assertThat(rs.getClob("c_clob").getSubString(1, 5)).isEqualTo("ccccc");
                    assertThat(rs.getBytes("c_bin")).containsExactly(bin);
                    assertThat(rs.getBytes("c_blob")).containsExactly(bin);
                    assertThat(rs.getBlob("c_blob").getBytes(1, bin.length)).containsExactly(bin);
                    assertThat(rs.getDate("c_date")).isEqualTo(java.sql.Date.valueOf("1969-12-31"));
                    assertThat(rs.getObject("c_date", LocalDate.class)).isEqualTo(LocalDate.of(1969, 12, 31));
                    assertThat(rs.getTimestamp("c_date")).isEqualTo(Timestamp.valueOf("1969-12-31 00:00:00"));
                    assertThat(rs.getObject("c_time", LocalTime.class)).isEqualTo(time);
                    assertThat(rs.getTime("c_time").toString()).isEqualTo("23:59:58");
                    assertThat(rs.getTimestamp("c_ts")).isEqualTo(ts);
                    assertThat(rs.getObject("c_ts", LocalDateTime.class)).isEqualTo(ts.toLocalDateTime());
                    assertThat(rs.getDate("c_ts")).isEqualTo(java.sql.Date.valueOf("1969-07-20"));
                    assertThat(rs.getString("c_ts")).isEqualTo("1969-07-20 20:17:40.123456789");
                    OffsetDateTime gotTz = rs.getObject("c_tstz", OffsetDateTime.class);
                    assertThat(gotTz.toInstant()).isEqualTo(tstz.toInstant());
                    assertThat(rs.getTimestamp("c_tstz").toInstant()).isEqualTo(tstz.toInstant());
                    assertThat(rs.getObject("c_tstz")).isInstanceOf(OffsetDateTime.class);
                    OffsetTime gotTimeTz = rs.getObject("c_timetz", OffsetTime.class);
                    assertThat(gotTimeTz.isEqual(timetz)).isTrue();
                    assertThat(rs.getObject("c_uuid", UUID.class)).isEqualTo(uuid);
                    assertThat(rs.getString("c_null")).isNull();
                    assertThat(rs.wasNull()).isTrue();
                    assertThat(rs.getInt("c_null")).isZero();
                    assertThat(rs.wasNull()).isTrue();
                    assertThat(rs.getObject("c_null")).isNull();
                    assertThat(rs.getString("c_str")).isNotNull();
                    assertThat(rs.wasNull()).isFalse();

                    // getObject(i) must return exactly the class ResultSetMetaData.getColumnClassName announces
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object o = rs.getObject(i);
                        if (o != null) {
                            assertThat(o.getClass().getName()).as("class of column " + md.getColumnLabel(i))
                                    .isEqualTo(md.getColumnClassName(i));
                        }
                    }
                    assertThat(md.getColumnType(5)).isEqualTo(Types.DECIMAL);
                    assertThat(md.getPrecision(5)).isEqualTo(12);
                    assertThat(md.getScale(5)).isEqualTo(3);
                    assertThat(md.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
                    assertThat(md.isNullable(20)).isEqualTo(ResultSetMetaData.columnNullable);
                    assertThat(md.getTableName(1)).isEqualToIgnoringCase("types_t");
                    assertThat(md.getColumnLabel(10)).isEqualToIgnoringCase("c_str");
                    assertThat(rs.getObject("c_ts")).isInstanceOf(Timestamp.class);
                    assertThat(rs.getObject("c_date")).isInstanceOf(java.sql.Date.class);
                    assertThat(rs.getObject("c_dec")).isInstanceOf(BigDecimal.class);
                    assertThat(rs.getObject("c_big")).isInstanceOf(Long.class);
                    assertThat(rs.next()).isFalse();
                }
            }
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void oracleNumberArrivesAsDecimalAndConvertsOnTheDriver() throws Exception {
        try (Connection c = connect("h2ora"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, amount, v FROM ora_t")) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getMetaData().getColumnType(1)).isIn(Types.NUMERIC, Types.DECIMAL);
            assertThat(rs.getObject(1)).isInstanceOf(BigDecimal.class);
            assertThat(rs.getInt(1)).isEqualTo(7);
            assertThat(rs.getLong(1)).isEqualTo(7L);
            assertThat(rs.getShort(1)).isEqualTo((short) 7);
            assertThat(rs.getDouble(2)).isEqualTo(12.5d);
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("12.5");
            assertThat(rs.getObject(1, Integer.class)).isEqualTo(7);
            assertThat(rs.getObject(1, Long.class)).isEqualTo(7L);
            assertThat(rs.getString(1)).isEqualTo("7");
            assertThat(c.getMetaData().getURL()).isEqualTo(url("h2ora"));
        }
    }

    @Test
    void timestampsSurviveDstEdgesAndNegativeEpoch() throws Exception {
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        try (Connection c = connect("h2")) {
            List<Timestamp> values = List.of(
                    Timestamp.valueOf("2024-03-31 01:59:59.999999999"),   // just before the spring gap
                    Timestamp.valueOf("2024-03-31 03:00:00"),             // first instant after the gap
                    Timestamp.valueOf("2024-10-27 02:30:00"),             // ambiguous (autumn overlap)
                    Timestamp.valueOf("2024-10-27 03:30:00.5"),
                    Timestamp.valueOf("1969-07-20 20:17:40.123456789"),   // negative epoch
                    Timestamp.valueOf("1900-01-01 00:00:00"),
                    Timestamp.valueOf("2099-12-31 23:59:59.999"));
            List<LocalDate> dates = List.of(LocalDate.of(1900, 1, 1), LocalDate.of(1969, 12, 31), LocalDate.of(1970, 1, 1),
                    LocalDate.of(2024, 3, 31), LocalDate.of(2024, 10, 27), LocalDate.of(9999, 12, 31), LocalDate.of(1, 1, 1));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ts_t VALUES (?, ?, ?)")) {
                for (int i = 0; i < values.size(); i++) {
                    ps.setInt(1, i);
                    ps.setTimestamp(2, values.get(i));
                    ps.setDate(3, java.sql.Date.valueOf(dates.get(i)));
                    ps.addBatch();
                }
                assertThat(ps.executeBatch()).hasSize(values.size());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, d FROM ts_t WHERE id = ?")) {
                for (int i = 0; i < values.size(); i++) {
                    ps.setInt(1, i);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        Timestamp expected = values.get(i);
                        assertThat(rs.getTimestamp(1)).as("timestamp " + expected).isEqualTo(expected);
                        assertThat(rs.getObject(1, LocalDateTime.class)).isEqualTo(expected.toLocalDateTime());
                        assertThat(rs.getDate(2).toLocalDate()).isEqualTo(dates.get(i));
                        assertThat(rs.getObject(2, LocalDate.class)).isEqualTo(dates.get(i));
                    }
                }
            }
            // the same value compared on the server side: wall-clock semantics are preserved end to end
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ts_t WHERE ts = ?")) {
                ps.setTimestamp(1, Timestamp.valueOf("2024-10-27 02:30:00"));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    // ------------------------------------------------------------------ statements, cursors, options

    @Test
    void cursorsStreamWithFetchSizeAndMaxRows() throws Exception {
        try (Connection c = connect("h2"); Statement st = c.createStatement()) {
            st.setFetchSize(2);
            List<Integer> all = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT n FROM nums ORDER BY n")) {
                assertThat(rs.isBeforeFirst()).isTrue();
                while (rs.next()) {
                    all.add(rs.getInt(1));
                    assertThat(rs.getRow()).isEqualTo(all.size());
                    if (all.size() == 1) {
                        assertThat(session(c).isPinned()).as("open cursor pins").isTrue();
                        assertThat(rs.isFirst()).isTrue();
                    }
                }
                assertThat(rs.isAfterLast()).isTrue();
            }
            assertThat(all).containsExactly(1, 2, 3, 4, 5);
            assertThat(session(c).isPinned()).isFalse();
            assertThat(session(c).openCursors()).isZero();

            st.setMaxRows(3);
            st.setFetchSize(10);
            try (ResultSet rs = st.executeQuery("SELECT n FROM nums ORDER BY n")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                assertThat(n).isEqualTo(3);
            }
            st.setMaxRows(0);
            st.setFetchSize(1);
            try (ResultSet rs = st.executeQuery("SELECT n FROM nums ORDER BY n")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                // closing early sends CLOSE_CURSOR and un-pins
            }
            assertThat(session(c).openCursors()).isZero();
            assertThat(session(c).isPinned()).isFalse();
            // a second statement on the same connection while a cursor is open
            try (Statement other = c.createStatement()) {
                other.setFetchSize(1);
                try (ResultSet a = st.executeQuery("SELECT n FROM nums ORDER BY n");
                     ResultSet b = other.executeQuery("SELECT n FROM nums ORDER BY n DESC")) {
                    assertThat(a.next() && b.next()).isTrue();
                    assertThat(a.getInt(1)).isEqualTo(1);
                    assertThat(b.getInt(1)).isEqualTo(5);
                    assertThat(session(c).openCursors()).isEqualTo(2);
                }
            }
            assertThat(session(c).openCursors()).isZero();
        }
    }

    @Test
    void statementOptionsAreNotStickyOnTheCachedPhysicalStatement() throws Exception {
        try (Connection c = connect("h2")) {
            c.setAutoCommit(false); // keeps the session pinned, so the gateway caches the physical PreparedStatement
            try (PreparedStatement ps = c.prepareStatement("SELECT n FROM nums ORDER BY n")) {
                ps.setMaxRows(2);
                assertThat(count(ps.executeQuery())).isEqualTo(2);
                assertThat(session(c).isPinned()).isTrue();
                ps.setMaxRows(0);
                assertThat(count(ps.executeQuery())).as("maxRows reset to 0 must apply to the cached statement").isEqualTo(5);
                ps.setMaxRows(4);
                assertThat(count(ps.executeQuery())).isEqualTo(4);
                ps.setQueryTimeout(5);
                assertThat(count(ps.executeQuery())).isEqualTo(4);
                ps.setQueryTimeout(0);
                assertThat(count(ps.executeQuery())).isEqualTo(4);
            }
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    private static int count(ResultSet rs) throws SQLException {
        try (rs) {
            int n = 0;
            while (rs.next()) {
                n++;
            }
            return n;
        }
    }

    @Test
    void executeFamilyUpdateCountsGeneratedKeysAndResultShapeErrors() throws Exception {
        try (Connection c = connect("h2"); Statement st = c.createStatement()) {
            assertThat(st.execute("INSERT INTO tx_t VALUES (900, 'a'), (901, 'b')")).isFalse();
            assertThat(st.getUpdateCount()).isEqualTo(2);
            assertThat(st.getResultSet()).isNull();
            assertThat(st.getMoreResults()).isFalse();
            assertThat(st.getUpdateCount()).isEqualTo(-1);
            assertThat(st.execute("SELECT n FROM nums")).isTrue();
            assertThat(st.getResultSet()).isNotNull();
            assertThat(st.getUpdateCount()).isEqualTo(-1);
            st.getResultSet().close();

            assertThat(st.executeUpdate("INSERT INTO keys_t (v) VALUES ('k1')", Statement.RETURN_GENERATED_KEYS)).isEqualTo(1);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isPositive();
                assertThat(keys.getMetaData().getColumnLabel(1)).isEqualToIgnoringCase("id");
                assertThat(keys.next()).isFalse();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO keys_t (v) VALUES (?)", new String[] {"ID"})) {
                ps.setString(1, "k2");
                assertThat(ps.executeUpdate()).isEqualTo(1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getObject(1)).isInstanceOf(Long.class);
                }
                // a second execution of the same prepared statement delivers fresh keys
                ps.setString(1, "k3");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO keys_t (v) VALUES (?)")) {
                ps.setString(1, "k4");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).as("no keys requested -> empty result set, not an error").isFalse();
                }
            }

            assertThatThrownBy(() -> st.executeQuery("INSERT INTO tx_t VALUES (902, 'c')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07005"));
            assertThatThrownBy(() -> st.executeUpdate("SELECT 1"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07005"));
            assertThatThrownBy(() -> st.executeQuery("SELEC 1"))
                    .isInstanceOf(SQLSyntaxErrorException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).startsWith("42"));
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM does_not_exist"))
                    .isInstanceOf(SQLSyntaxErrorException.class);
            // the connection survives every one of these
            assertThat(c.isValid(2)).isTrue();
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_t WHERE id IN (900, 901, 902)")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(3); // 902 was inserted: H2 ran it before the shape check (documented)
            }
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void batchesThroughTheDriver() throws Exception {
        try (Connection c = connect("h2")) {
            try (Statement st = c.createStatement()) {
                st.addBatch("INSERT INTO batch_t VALUES (1, 'a')");
                st.addBatch("INSERT INTO batch_t VALUES (2, 'b')");
                st.addBatch("UPDATE batch_t SET v = 'c'");
                assertThat(st.executeBatch()).containsExactly(1, 1, 2);
                assertThat(st.executeBatch()).isEmpty();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO batch_t VALUES (?, ?)")) {
                for (int i = 10; i < 13; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "v" + i);
                    ps.addBatch();
                }
                assertThat(ps.executeLargeBatch()).containsExactly(1L, 1L, 1L);
                ps.setInt(1, 20);
                ps.setString(2, "x");
                ps.addBatch();
                ps.setInt(1, 1); // duplicate key
                ps.setString(2, "dup");
                ps.addBatch();
                assertThatThrownBy(ps::executeBatch)
                        .isInstanceOf(BatchUpdateException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            }
            assertThat(session(c).isPinned()).isFalse();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM batch_t")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(5);
            }
        }
    }

    // ------------------------------------------------------------------ transactions

    @Test
    void transactionsSavepointsAndVisibility() throws Exception {
        try (Connection c = connect("h2"); Connection other = connect("h2")) {
            c.setAutoCommit(false);
            assertThat(c.getAutoCommit()).isFalse();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tx_t VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "rolled back");
                ps.executeUpdate();
                assertThat(session(c).isPinned()).isTrue();
                assertThat(session(c).inTransaction()).isTrue();
                assertThat(scalar(other, "SELECT COUNT(*) FROM tx_t WHERE id = 1")).isEqualTo(0L);
                c.rollback();
                assertThat(session(c).isPinned()).isFalse();
                assertThat(scalar(other, "SELECT COUNT(*) FROM tx_t WHERE id = 1")).isEqualTo(0L);

                ps.setInt(1, 2);
                ps.setString(2, "committed");
                ps.executeUpdate();
                Savepoint sp = c.setSavepoint();
                ps.setInt(1, 3);
                ps.setString(2, "undone");
                ps.executeUpdate();
                c.rollback(sp);
                assertThat(session(c).inTransaction()).isTrue();
                Savepoint named = c.setSavepoint("my_sp");
                assertThat(named.getSavepointName()).isEqualTo("my_sp");
                c.releaseSavepoint(named);
                c.commit();
                assertThat(session(c).isPinned()).isFalse();
                assertThat(scalar(other, "SELECT COUNT(*) FROM tx_t WHERE id IN (2, 3)")).isEqualTo(1L);

                ps.setInt(1, 4);
                ps.setString(2, "via autocommit");
                ps.executeUpdate();
                c.setAutoCommit(true); // commits
                assertThat(scalar(other, "SELECT COUNT(*) FROM tx_t WHERE id = 4")).isEqualTo(1L);
                assertThat(session(c).isPinned()).isFalse();
            }
            assertThatThrownBy(() -> c.rollback(new Savepoint() {
                public int getSavepointId() { return 0; }
                public String getSavepointName() { return "foreign"; }
            })).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void cursorOpenAcrossCommitDoesNotBreakTheProtocol() throws Exception {
        try (Connection c = connect("h2"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.setFetchSize(1);
            int seen = 0;
            try (ResultSet rs = st.executeQuery("SELECT n FROM nums ORDER BY n")) {
                assertThat(rs.next()).isTrue();
                seen++;
                c.commit();
                assertThat(session(c).isPinned()).as("open cursor keeps the pin after COMMIT").isTrue();
                try {
                    while (rs.next()) {
                        seen++;
                    }
                } catch (SQLException e) {
                    // the physical driver may have closed the cursor at COMMIT: acceptable, but never fatal
                    assertThat((Throwable) e).isNotInstanceOf(SQLNonTransientConnectionException.class);
                }
            }
            assertThat(seen).isBetween(1, 5);
            assertThat(c.isValid(1)).isTrue();
            assertThat(session(c).openCursors()).isZero();
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    private static Object scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void databaseMetaDataScalarsAndResultSetMethods() throws Exception {
        try (Connection c = connect("h2")) {
            DatabaseMetaData md = c.getMetaData();
            assertThat(md.getDatabaseProductName()).isEqualTo("H2");
            assertThat(md.getDatabaseMajorVersion()).isEqualTo(2);
            assertThat(md.getDatabaseProductVersion()).isNotBlank();
            assertThat(md.getUserName()).isEqualToIgnoringCase("sa");
            assertThat(md.getURL()).isEqualTo(url("h2"));
            assertThat(md.getIdentifierQuoteString()).isEqualTo("\"");
            assertThat(md.supportsSavepoints()).isTrue();
            assertThat(md.supportsBatchUpdates()).isTrue();
            assertThat(md.getDefaultTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(md.storesUpperCaseIdentifiers()).isTrue();

            try (ResultSet rs = md.getTables(null, "PUBLIC", "TYPES_T", new String[] {"TABLE", "VIEW"})) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("TABLE_NAME")).isEqualTo("TYPES_T");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = md.getTables(null, "PUBLIC", "%", null)) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                assertThat(n).isGreaterThanOrEqualTo(7);
            }
            try (ResultSet rs = md.getTables(null, "PUBLIC", "%", new String[0])) {
                assertThat(rs.next()).as("empty type array = no types match (H2)").isFalse();
            }
            int cols = 0;
            try (ResultSet rs = md.getColumns(null, "PUBLIC", "TYPES_T", "%")) {
                while (rs.next()) {
                    cols++;
                    assertThat(rs.getInt("DATA_TYPE")).isNotNull();
                    assertThat(rs.getString("COLUMN_NAME")).isNotBlank();
                }
            }
            assertThat(cols).isEqualTo(20);
            try (ResultSet rs = md.getPrimaryKeys(null, "PUBLIC", "TYPES_T")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("ID");
            }
            try (ResultSet rs = md.getIndexInfo(null, "PUBLIC", "TYPES_T", true, true)) {
                assertThat(rs.next()).isTrue(); // the primary key index
            }
            try (ResultSet rs = md.getBestRowIdentifier(null, "PUBLIC", "TYPES_T", DatabaseMetaData.bestRowSession, true)) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = md.getUDTs(null, null, "%", new int[] {Types.JAVA_OBJECT, Types.STRUCT})) {
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = md.getUDTs(null, null, "%", null)) {
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = md.getSchemas()) {
                List<String> schemas = new ArrayList<>();
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
                assertThat(schemas).contains("PUBLIC", "INFORMATION_SCHEMA");
            }
            try (ResultSet rs = md.getSchemas(null, "PUBLIC")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = md.getTypeInfo()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("TYPE_NAME")).isNotBlank();
            }
            try (ResultSet rs = md.getCatalogs()) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = md.getTableTypes()) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = md.getProcedures(null, "PUBLIC", "%")) {
                rs.next();
            }
            try (ResultSet rs = md.getClientInfoProperties()) {
                rs.next();
            }
            try (ResultSet rs = md.getImportedKeys(null, "PUBLIC", "TYPES_T")) {
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = md.getCrossReference(null, "PUBLIC", "NUMS", null, "PUBLIC", "TYPES_T")) {
                assertThat(rs.next()).isFalse();
            }
            assertThat(session(c).isPinned()).isFalse();
            assertThat(session(c).openCursors()).isZero();
        }
    }

    // ------------------------------------------------------------------ lifecycle, errors, concurrency

    @Test
    void closeOrderingAndClosedConnectionBehaviour() throws Exception {
        Connection c = connect("h2");
        Statement st = c.createStatement();
        st.setFetchSize(1);
        ResultSet rs = st.executeQuery("SELECT n FROM nums ORDER BY n");
        assertThat(rs.next()).isTrue();
        PreparedStatement ps = c.prepareStatement("SELECT 1");
        ps.executeQuery().close();
        int sessions = gw.gateway.sessions().size();
        c.close();
        assertThat(c.isClosed()).isTrue();
        assertThatCode(rs::close).doesNotThrowAnyException();
        assertThatCode(st::close).doesNotThrowAnyException();
        assertThatCode(ps::close).doesNotThrowAnyException();
        assertThatCode(c::close).doesNotThrowAnyException();
        assertThat(c.isValid(1)).isFalse();
        assertThat(rs.isClosed()).isTrue();
        assertThat(st.isClosed()).isTrue();
        assertThatThrownBy(c::createStatement).isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
        assertThatThrownBy(() -> st.executeQuery("SELECT 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> rs.getInt(1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(c::getMetaData).isInstanceOf(SQLNonTransientConnectionException.class);
        assertThatThrownBy(c::commit).isInstanceOf(SQLNonTransientConnectionException.class);
        // the gateway released everything
        long deadline = System.currentTimeMillis() + 5000;
        while (gw.gateway.sessions().size() >= sessions && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(gw.gateway.sessions().size()).isLessThan(sessions);
    }

    @Test
    void idleTimeoutIsAFatalConnectionFailureOnTheDriver() throws Exception {
        GatewayConfig cfg = GatewayConfig.embedded("gw-idle").withIdleTimeoutSeconds(1);
        StaticConfig sc = new StaticConfig("gw-idle", List.of(
                StaticConfig.DatasourceConfig.of("h2", "H2", URL, "sa", "", "TRANSACTION", 2)), List.of());
        try (GatewayFixture idle = GatewayFixture.start(cfg, sc)) {
            Connection c = DriverManager.getConnection("jdbc:dbp://127.0.0.1:" + idle.port() + "/h2");
            assertThat(c.isValid(1)).isTrue();
            Thread.sleep(1700);
            assertThatThrownBy(() -> c.createStatement().executeQuery("SELECT 1"))
                    .isInstanceOf(SQLNonTransientConnectionException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).startsWith("08"));
            assertThat(c.isClosed()).isTrue();
            assertThat(c.isValid(1)).isFalse();
            assertThatThrownBy(c::createStatement).isInstanceOf(SQLNonTransientConnectionException.class);
            long deadline = System.currentTimeMillis() + 5000;
            while (idle.gateway.sessions().size() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(idle.gateway.sessions().size()).isZero();
            assertThatCode(c::close).doesNotThrowAnyException();
        }
    }

    @Test
    void isValidFromAnotherThreadWhileTheOwnerIsIdle() throws Exception {
        try (Connection c = connect("h2")) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> fs = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    fs.add(ex.submit(() -> c.isValid(2)));
                }
                for (Future<Boolean> f : fs) {
                    assertThat(f.get(10, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                ex.shutdownNow();
            }
            c.setNetworkTimeout(Runnable::run, 5000);
            assertThat(c.getNetworkTimeout()).isEqualTo(5000);
            c.setNetworkTimeout(Runnable::run, 0);
            assertThat(scalar(c, "SELECT 1")).isEqualTo(1);
        }
    }

    @Test
    void connectionSettingsAreReappliedAcrossPhysicalConnections() throws Exception {
        try (Connection c = connect("h2")) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setClientInfo("ApplicationName", "rt-test");
            c.setReadOnly(true);
            assertThat(c.isReadOnly()).isTrue();
            assertThat(c.getClientInfo("ApplicationName")).isEqualTo("rt-test");
            for (int i = 0; i < 3; i++) {
                assertThat(scalar(c, "SELECT ISOLATION_LEVEL FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = SESSION_ID()")
                        .toString()).isEqualTo("SERIALIZABLE");
                assertThat(session(c).isPinned()).isFalse();
            }
            c.setReadOnly(false);
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        }
    }

    @Test
    void manyDriverConnectionsShareFivePhysicalConnections() throws Exception {
        int sessions = 25;
        int perSession = 12;
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final int sid = i;
            fs.add(ex.submit(() -> {
                int ok = 0;
                try (Connection c = connect("h2")) {
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO load_t (v) VALUES (?)");
                         PreparedStatement sel = c.prepareStatement("SELECT COUNT(*) FROM load_t WHERE v = ?")) {
                        for (int j = 0; j < perSession; j++) {
                            if (j % 3 == 0) {
                                c.setAutoCommit(false);
                                ins.setInt(1, sid * 1000 + j);
                                ins.executeUpdate();
                                c.commit();
                                c.setAutoCommit(true);
                            } else if (j % 3 == 1) {
                                ins.setInt(1, sid * 1000 + j);
                                assertThat(ins.executeUpdate()).isEqualTo(1);
                            } else {
                                sel.setInt(1, sid * 1000 + j - 1);
                                try (ResultSet rs = sel.executeQuery()) {
                                    rs.next();
                                    assertThat(rs.getLong(1)).isEqualTo(1L);
                                }
                            }
                            ok++;
                        }
                    }
                }
                return ok;
            }));
        }
        int total = 0;
        for (Future<Integer> f : fs) {
            total += f.get(120, TimeUnit.SECONDS);
        }
        ex.shutdown();
        assertThat(total).isEqualTo(sessions * perSession);
        assertThat(gw.gateway.pools().poolsFor("h2").get(0).totalConnections()).isLessThanOrEqualTo(5);
        assertThat(gw.gateway.pools().poolsFor("h2").get(0).activeConnections()).isZero();
    }
}
