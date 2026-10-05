package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Execute;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the JDBC-generic code paths frameworks take at startup and per request (Spring JdbcTemplate,
 * Hibernate dialect resolution, MyBatis, Flyway) without the frameworks on the classpath, and compares a few
 * value conversions with a real H2 connection.
 */
class FrameworkSmokeTest extends GatewayTest {

    @Test
    void dialectResolutionAndSchemaInspection() throws Exception {
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            // Hibernate's dialect resolver and Flyway's database type detection
            assertThat(md.getDatabaseProductName()).isEqualTo("H2");
            assertThat(md.getDatabaseMajorVersion()).isEqualTo(2);
            assertThat(md.getDatabaseMinorVersion()).isEqualTo(3);
            assertThat(md.getDriverName()).isNotBlank();
            assertThat(md.supportsBatchUpdates()).isTrue();
            assertThat(md.supportsGetGeneratedKeys()).isTrue();
            assertThat(md.getSQLKeywords()).isNotNull();
            assertThat(md.supportsRefCursors()).isTrue();
            assertThat(md.getIdentifierQuoteString()).isEqualTo("\"");
            // schema validation (hbm2ddl validate / Flyway baseline)
            try (ResultSet tables = md.getTables(null, null, "%", new String[] {"TABLE"})) {
                List<String> names = new ArrayList<>();
                while (tables.next()) {
                    names.add(tables.getString("TABLE_NAME"));
                }
                assertThat(names).containsExactly("CUSTOMERS", "ORDERS");
            }
            try (ResultSet types = md.getTypeInfo()) {
                assertThat(types.next()).isTrue();
            }
            // Hibernate probes LOB creation and transaction control
            assertThat(c.createClob()).isNotNull();
            c.setAutoCommit(false);
            c.commit();
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    @Test
    void jdbcTemplateStyleInsertWithGeneratedKeysAndQuery() throws Exception {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("insert into orders (customer_id, total) values (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, 7L);
                ps.setBigDecimal(2, new java.math.BigDecimal("19.99"));
                assertThat(ps.executeUpdate()).isEqualTo(1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    // Spring's GeneratedKeyHolder reads the key by index and through getObject
                    assertThat(keys.getLong(1)).isEqualTo(101L);
                    assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("select id, name from customers where id > ?")) {
                ps.setFetchSize(50);
                ps.setInt(1, 0);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(gateway.last(Execute.class).options().fetchSize()).isEqualTo(50);
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> labels = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        labels.add(md.getColumnLabel(i));
                    }
                    assertThat(labels).containsExactly("ID", "NAME");
                    int rows = 0;
                    while (rs.next()) {
                        // RowMapper style access by label and index, plus getObject for BeanPropertyRowMapper
                        assertThat(rs.getObject("ID")).isInstanceOf(Integer.class);
                        assertThat(rs.getString(2)).isNotBlank();
                        rows++;
                    }
                    assertThat(rows).isEqualTo(3);
                }
            }
            // MyBatis / Hibernate batching
            try (PreparedStatement ps = c.prepareStatement("update customers set name = ? where id = ?")) {
                for (int i = 1; i <= 3; i++) {
                    ps.setString(1, "n" + i);
                    ps.setInt(2, i);
                    ps.addBatch();
                }
                assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
            }
        }
    }

    @Test
    void conversionsMatchH2ForCommonCases() throws Exception {
        String h2Url = "jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1";
        try (Connection h2 = DriverManager.getConnection(h2Url);
             Statement hs = h2.createStatement();
             ResultSet hr = hs.executeQuery("select cast('2024-01-15 10:20:30.5' as timestamp) as ts, 42 as i,"
                     + " cast(12.50 as numeric(5,2)) as d, 'true' as b, cast('2024-01-15' as date) as dt,"
                     + " cast('10:20:30' as time) as t, cast(9000000000 as bigint) as l, cast('123.45' as varchar) as ns");
             Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select types")) {
            assertThat(hr.next()).isTrue();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("C_TS")).isEqualTo(hr.getString("ts"));
            assertThat(rs.getTimestamp("C_TS")).isEqualTo(hr.getTimestamp("ts"));
            assertThat(rs.getDate("C_TS")).isEqualTo(hr.getDate("ts"));
            assertThat(rs.getString("C_INT")).isEqualTo(hr.getString("i"));
            assertThat(rs.getLong("C_INT")).isEqualTo(hr.getLong("i"));
            assertThat(rs.getDouble("C_INT")).isEqualTo(hr.getDouble("i"));
            assertThat(rs.getString("C_DEC")).isEqualTo(hr.getString("d"));
            assertThat(rs.getBigDecimal("C_DEC")).isEqualTo(hr.getBigDecimal("d"));
            assertThat(rs.getDouble("C_DEC")).isEqualTo(hr.getDouble("d"));
            assertThat(rs.getBoolean("C_BOOLSTR")).isEqualTo(hr.getBoolean("b"));
            assertThat(rs.getDate("C_DATE")).isEqualTo(hr.getDate("dt"));
            assertThat(rs.getString("C_DATE")).isEqualTo(hr.getString("dt"));
            assertThat(rs.getTimestamp("C_DATE")).isEqualTo(hr.getTimestamp("dt"));
            assertThat(rs.getTime("C_TIME")).isEqualTo(hr.getTime("t"));
            assertThat(rs.getString("C_TIME")).isEqualTo(hr.getString("t"));
            assertThat(rs.getLong("C_LONG")).isEqualTo(hr.getLong("l"));
            assertThat(rs.getString("C_LONG")).isEqualTo(hr.getString("l"));
            assertThat(rs.getDouble("C_NUMSTR")).isEqualTo(hr.getDouble("ns"));
            assertThat(rs.getBigDecimal("C_NUMSTR")).isEqualTo(hr.getBigDecimal("ns"));
            assertThat(rs.getObject("C_TS", java.time.LocalDateTime.class)).isEqualTo(hr.getObject("ts", java.time.LocalDateTime.class));
            assertThat(rs.getObject("C_DATE", java.time.LocalDate.class)).isEqualTo(hr.getObject("dt", java.time.LocalDate.class));
        }
    }
}
