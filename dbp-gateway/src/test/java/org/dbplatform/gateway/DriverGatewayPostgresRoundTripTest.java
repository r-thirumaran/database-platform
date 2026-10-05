package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.session.LogicalSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real driver against an in-process gateway backed by an embedded PostgreSQL: callable statements with OUT /
 * INOUT parameters, {@code refcursor} OUT parameters surfacing as {@link ResultSet} (specification section 4.7),
 * PostgreSQL-specific types bound as STRING, RETURNING-based generated keys and aborted-transaction semantics.
 */
class DriverGatewayPostgresRoundTripTest {

    static final String DB = "dbprt";
    static EmbeddedPg pg;
    static GatewayFixture gw;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(DriverGatewayRoundTripTest.driverPresent(),
                "dbp-jdbc classes are not on the test classpath (run `mvn -pl dbp-jdbc test` first)");
        pg = EmbeddedPg.start();
        try (Connection c = pg.superuser("postgres"); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        try (Connection c = pg.superuser(DB); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id serial PRIMARY KEY, name text NOT NULL)");
            st.execute("INSERT INTO orders (name) VALUES ('a'), ('b'), ('c'), ('d'), ('e')");
            st.execute("CREATE TABLE typed_t (id uuid PRIMARY KEY, doc jsonb, created timestamptz, t time(3), d date,"
                    + " n numeric(10,2), ba bytea, b boolean, big bigint, txt text)");
            st.execute("CREATE FUNCTION add_one(INOUT x integer, OUT y text) AS $$ BEGIN x := x + 1; y := 'v' || x; END $$ LANGUAGE plpgsql");
            st.execute("CREATE FUNCTION open_orders() RETURNS refcursor AS $$ DECLARE c refcursor;"
                    + " BEGIN OPEN c FOR SELECT id, name FROM orders ORDER BY id; RETURN c; END $$ LANGUAGE plpgsql");
        }
        gw = GatewayFixture.start("gw-pgrt", List.of(
                StaticConfig.DatasourceConfig.of("pg", "POSTGRES", pg.jdbcUrl(DB), "postgres", "postgres", "TRANSACTION", 5)
                        .withConnectionTimeoutMs(1000)));
    }

    @AfterAll
    static void stop() throws Exception {
        if (gw != null) {
            gw.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:dbp://127.0.0.1:" + gw.port() + "/pg");
    }

    static LogicalSession session(Connection c) throws Exception {
        String id = (String) c.getClass().getMethod("getSessionId").invoke(c);
        return gw.gateway.sessions().sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void callableWithInoutAndOutParameters() throws Exception {
        try (Connection c = connect(); CallableStatement cs = c.prepareCall("{call add_one(?, ?)}")) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            cs.setInt(1, 41);
            cs.registerOutParameter(1, Types.INTEGER);
            cs.registerOutParameter(2, Types.VARCHAR);
            assertThat(cs.execute()).isFalse();
            assertThat(cs.getInt(1)).isEqualTo(42);
            assertThat(cs.wasNull()).isFalse();
            assertThat(cs.getString(2)).isEqualTo("v42");
            assertThat(cs.getObject(1)).isEqualTo(42);
            assertThat(cs.getObject(2, String.class)).isEqualTo("v42");
            assertThat(cs.getMoreResults()).isFalse();
            // second execution with a new INOUT value
            cs.setInt(1, 1);
            cs.execute();
            assertThat(cs.getInt(1)).isEqualTo(2);
            assertThatThrownBy(() -> cs.getInt(3)).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07009"));
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void refCursorOutParameterSurfacesAsResultSet() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (CallableStatement cs = c.prepareCall("{? = call open_orders()}")) {
                cs.registerOutParameter(1, Types.REF_CURSOR);
                cs.setFetchSize(2);
                assertThat(cs.execute()).as("the cursor item belongs to the OUT parameter, not to getResultSet").isFalse();
                assertThat(cs.getResultSet()).isNull();
                assertThat(cs.getUpdateCount()).isEqualTo(-1);
                Object out = cs.getObject(1);
                assertThat(out).isInstanceOf(ResultSet.class);
                assertThat(cs.wasNull()).isFalse();
                try (ResultSet rs = (ResultSet) out) {
                    ResultSetMetaData md = rs.getMetaData();
                    assertThat(md.getColumnCount()).isEqualTo(2);
                    assertThat(md.getColumnLabel(1)).isEqualTo("id");
                    assertThat(session(c).openCursors()).isEqualTo(1);
                    List<String> names = new ArrayList<>();
                    while (rs.next()) {
                        names.add(rs.getInt("id") + ":" + rs.getString("name"));
                    }
                    assertThat(names).containsExactly("1:a", "2:b", "3:c", "4:d", "5:e");
                }
                assertThat(cs.getObject(1, ResultSet.class)).isNotNull();
                assertThat(cs.getMoreResults()).isFalse();
                assertThat(session(c).openCursors()).isZero();
                assertThat(session(c).isPinned()).as("transaction still open").isTrue();
            }
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void postgresTypesBoundAsStringAndReadBack() throws Exception {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        OffsetDateTime created = OffsetDateTime.of(2024, 2, 29, 13, 45, 30, 123456000, ZoneOffset.ofHours(2));
        LocalTime t = LocalTime.of(23, 59, 58, 123_000_000);
        byte[] ba = {1, 2, 3, (byte) 0xFF};
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO typed_t VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, id);
                ps.setString(2, "{\"a\": 1, \"b\": [1, 2]}");
                ps.setObject(3, created);
                ps.setObject(4, t);
                ps.setDate(5, java.sql.Date.valueOf("1969-12-31"));
                ps.setBigDecimal(6, new BigDecimal("12.50"));
                ps.setBytes(7, ba);
                ps.setBoolean(8, true);
                ps.setLong(9, 9_000_000_000L);
                ps.setNull(10, Types.VARCHAR);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, doc, created, t, d, n, ba, b, big, txt, doc ->> 'a' AS a"
                    + " FROM typed_t WHERE id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject("id", UUID.class)).isEqualTo(id);
                    assertThat(rs.getString("id")).isEqualTo(id.toString());
                    assertThat(rs.getString("doc")).contains("\"a\": 1");
                    assertThat(rs.getString("a")).isEqualTo("1");
                    assertThat(rs.getObject("created", OffsetDateTime.class).toInstant()).isEqualTo(created.toInstant());
                    assertThat(rs.getTimestamp("created").toInstant()).isEqualTo(created.toInstant());
                    assertThat(rs.getObject("t", LocalTime.class)).isEqualTo(t);
                    assertThat(rs.getDate("d").toLocalDate()).isEqualTo(LocalDate.of(1969, 12, 31));
                    assertThat(rs.getBigDecimal("n")).isEqualTo(new BigDecimal("12.50"));
                    assertThat(rs.getBytes("ba")).containsExactly(ba);
                    assertThat(rs.getBoolean("b")).isTrue();
                    assertThat(rs.getLong("big")).isEqualTo(9_000_000_000L);
                    assertThat(rs.getString("txt")).isNull();
                    assertThat(rs.wasNull()).isTrue();
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object o = rs.getObject(i);
                        if (o != null) {
                            assertThat(o.getClass().getName()).as("class of " + md.getColumnLabel(i))
                                    .isEqualTo(md.getColumnClassName(i));
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM typed_t WHERE doc @> ?::jsonb AND id = ?")) {
                ps.setString(1, "{\"a\": 1}");
                ps.setString(2, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(1L);
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM typed_t");
            }
        }
    }

    @Test
    void generatedKeysViaReturning() throws Exception {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "gen");
                assertThat(ps.executeUpdate()).isEqualTo(1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getInt("id")).isGreaterThan(5);
                    assertThat(keys.getString("name")).isEqualTo("gen");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (name) VALUES (?)", new String[] {"id"})) {
                ps.setString(1, "gen2");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isPositive();
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM orders WHERE name LIKE 'gen%'");
            }
        }
    }

    @Test
    void abortedTransactionSemanticsAndFailedBatch() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 1000);
                ps.setString(2, "x");
                ps.executeUpdate();
                ps.setInt(1, 1);
                ps.setString(2, "dup");
                assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLIntegrityConstraintViolationException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
                assertThat(session(c).isPinned()).as("the aborted transaction stays on its physical connection").isTrue();
                // PostgreSQL: every statement until ROLLBACK fails with 25P02
                assertThatThrownBy(() -> c.createStatement().executeQuery("SELECT 1"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("25P02"));
                c.rollback();
                assertThat(session(c).isPinned()).isFalse();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM orders WHERE id = 1000")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
                c.rollback();
            }
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 2000);
                ps.setString(2, "b1");
                ps.addBatch();
                ps.setInt(1, 1);
                ps.setString(2, "dup");
                ps.addBatch();
                assertThatThrownBy(ps::executeBatch).isInstanceOf(BatchUpdateException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            }
            assertThat(c.isValid(2)).isTrue();
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM orders WHERE id >= 1000");
            }
        }
    }

    @Test
    void largeResultStreamsInBatches() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.setFetchSize(100);
            int n = 0;
            long sum = 0;
            try (ResultSet rs = st.executeQuery("SELECT g, 'row-' || g FROM generate_series(1, 1000) g")) {
                while (rs.next()) {
                    n++;
                    sum += rs.getInt(1);
                    assertThat(rs.getString(2)).isEqualTo("row-" + n);
                }
            }
            assertThat(n).isEqualTo(1000);
            assertThat(sum).isEqualTo(500_500L);
            st.setMaxRows(250);
            try (ResultSet rs = st.executeQuery("SELECT g FROM generate_series(1, 1000) g")) {
                int m = 0;
                while (rs.next()) {
                    m++;
                }
                assertThat(m).isEqualTo(250);
            }
            assertThat(session(c).isPinned()).isFalse();
            assertThat(session(c).openCursors()).isZero();
        }
    }
}
