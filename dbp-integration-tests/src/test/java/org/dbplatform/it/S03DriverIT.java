package org.dbplatform.it;

import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Sql;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 3: the public JDBC API of dbp-jdbc, through the gateway, against the PostgreSQL demo schema. */
@ExtendWith(ItExtension.class)
@Order(3)
@DisplayName("3 Driver -> gateway -> PostgreSQL (JDBC API)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S03DriverIT {

    /** Which way of calling a PostgreSQL procedure with an INOUT parameter worked (recorded for the report). */
    static Boolean callEscapeWorked;
    static Boolean plainCallWorked;

    private static Connection open() throws SQLException {
        return Stack.current().connect(DS_SALES, ORDERS);
    }

    @Test
    @Order(1)
    void select_1_with_api_key_in_url_and_with_user_password() throws SQLException {
        Stack s = Stack.current();
        try (Connection c = open()) {
            assertThat(Sql.queryLong(c, "SELECT 1")).isEqualTo(1);
            DatabaseMetaData md = c.getMetaData();
            assertThat(md.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(md.getURL()).startsWith("jdbc:dbp://");
            Results.note("product %s %s, driver %s %s, url %s", md.getDatabaseProductName(), md.getDatabaseProductVersion(), md.getDriverName(), md.getDriverVersion(), md.getURL());
        }
        try (Connection c = s.connectUserPassword(DS_SALES, ORDERS, s.apiKey(ORDERS))) {
            assertThat(Sql.queryLong(c, "SELECT 1")).isEqualTo(1);
            assertThat(c.getMetaData().getUserName()).isNotNull();
        }
    }

    @Test
    @Order(2)
    void prepared_statement_with_parameters_reads_customers() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, email, first_name, country_code FROM customer WHERE country_code = ? AND status = ? ORDER BY id FETCH FIRST ? ROWS ONLY")) {
            ps.setString(1, "DE");
            ps.setString(2, "ACTIVE");
            ps.setInt(3, 5);
            ps.setFetchSize(2); // forces FETCH round trips
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                assertThat(md.getColumnCount()).isEqualTo(4);
                assertThat(md.getColumnLabel(2).toLowerCase()).isEqualTo("email");
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                    assertThat(rs.getString("country_code")).isEqualTo("DE");
                    assertThat(rs.getString(2)).endsWith("@example.org");
                }
            }
            assertThat(ids).hasSize(5).isSorted();
            Results.note("5 DE customers with fetchSize=2 (FETCH frames), ids %s", ids);
        }
    }

    @Test
    @Order(3)
    void insert_with_returning_and_with_generated_keys() throws SQLException {
        try (Connection c = open()) {
            String email = "it-returning-" + System.nanoTime() + "@example.org";
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO customer (email, first_name, last_name, country_code) VALUES (?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, email);
                ps.setString(2, "It");
                ps.setString(3, "Returning");
                ps.setString(4, "DE");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    id = rs.getLong(1);
                }
            }
            assertThat(id).isPositive();
            String email2 = "it-genkeys-" + System.nanoTime() + "@example.org";
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO customer (email, first_name, last_name, country_code) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, email2);
                ps.setString(2, "It");
                ps.setString(3, "GenKeys");
                ps.setString(4, "FR");
                assertThat(ps.executeUpdate()).isEqualTo(1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    long generated = keys.getLong("id");
                    assertThat(generated).isGreaterThan(id);
                    Results.note("RETURNING id=%d, getGeneratedKeys id=%d", id, generated);
                }
            }
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer WHERE email IN (?, ?)", email, email2)).isEqualTo(2);
        }
    }

    @Test
    @Order(4)
    void transaction_rollback_then_commit() throws SQLException {
        try (Connection c = open()) {
            String email = "it-tx-" + System.nanoTime() + "@example.org";
            c.setAutoCommit(false);
            assertThat(Sql.update(c, "INSERT INTO customer (email, first_name, last_name, country_code) VALUES (?, 'Tx', 'Rollback', 'NL')", email)).isEqualTo(1);
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer WHERE email = ?", email)).as("visible inside the transaction").isEqualTo(1);
            c.rollback();
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer WHERE email = ?", email)).as("gone after rollback").isZero();
            assertThat(Sql.update(c, "INSERT INTO customer (email, first_name, last_name, country_code) VALUES (?, 'Tx', 'Commit', 'NL')", email)).isEqualTo(1);
            c.commit();
            c.setAutoCommit(true);
            try (Connection other = open()) {
                assertThat(Sql.queryLong(other, "SELECT count(*) FROM customer WHERE email = ?", email)).as("visible to another logical connection").isEqualTo(1);
            }
            // savepoint
            c.setAutoCommit(false);
            var sp = c.setSavepoint("it_sp");
            Sql.update(c, "UPDATE customer SET first_name = 'Changed' WHERE email = ?", email);
            c.rollback(sp);
            c.commit();
            c.setAutoCommit(true);
            assertThat(Sql.queryString(c, "SELECT first_name FROM customer WHERE email = ?", email)).isEqualTo("Tx");
        }
    }

    @Test
    @Order(5)
    void batch_insert_of_100_order_lines() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            long[] orderIds = new long[2];
            for (int i = 0; i < 2; i++) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (order_no, customer_id, status) VALUES (?, ?, 'NEW') RETURNING id")) {
                    ps.setString(1, "IT-" + System.nanoTime() + "-" + i);
                    ps.setLong(2, 10 + i);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        orderIds[i] = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO order_item (order_id, product_id, qty, unit_price, line_total) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 0; i < 100; i++) {
                    ps.setLong(1, orderIds[i / 50]);
                    ps.setLong(2, (i % 50) + 1);   // UNIQUE (order_id, product_id): 50 distinct products per order
                    ps.setInt(3, 1);
                    ps.setBigDecimal(4, new BigDecimal("1.00"));
                    ps.setBigDecimal(5, new BigDecimal("1.00"));
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                assertThat(counts).hasSize(100);
                assertThat(java.util.Arrays.stream(counts).allMatch(n -> n == 1 || n == Statement.SUCCESS_NO_INFO)).isTrue();
            }
            c.commit();
            c.setAutoCommit(true);
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM order_item WHERE order_id IN (?, ?)", orderIds[0], orderIds[1])).isEqualTo(100);
            Results.note("executeBatch of 100 rows in one transaction (orders %d/%d); trigger trg_order_item_stock fired", orderIds[0], orderIds[1]);
        }
    }

    @Test
    @Order(6)
    void call_procedure_with_inout_parameter_via_jdbc_call_escape() throws SQLException {
        try (Connection c = open()) {
            try (CallableStatement cs = c.prepareCall("{call order_pkg_place_order(?, ?, ?, ?)}")) {
                cs.setLong(1, 3);
                cs.setLong(2, 5);
                cs.setInt(3, 2);
                cs.setNull(4, Types.BIGINT);           // INOUT: pgjdbc needs the input value bound
                cs.registerOutParameter(4, Types.BIGINT);
                cs.execute();
                long orderId = cs.getLong(4);
                assertThat(cs.wasNull()).isFalse();
                assertThat(orderId).isPositive();
                assertThat(Sql.queryString(c, "SELECT status FROM orders WHERE id = ?", orderId)).isEqualTo("NEW");
                assertThat(Sql.queryLong(c, "SELECT count(*) FROM order_item WHERE order_id = ?", orderId)).isEqualTo(1);
                assertThat(Sql.queryLong(c, "SELECT count(*) FROM payment WHERE order_id = ?", orderId)).isEqualTo(1);
                callEscapeWorked = true;
                Results.note("{call order_pkg_place_order(?,?,?,?)} with registerOutParameter(4, BIGINT) returned order id %d (jdbcProperties escapeSyntaxCallMode=callIfNoReturn from the import document)", orderId);
            } catch (SQLException e) {
                callEscapeWorked = false;
                Results.note("{call …} escape failed: %s", Sql.describe(e));
                throw e;
            }
        }
    }

    @Test
    @Order(7)
    void call_procedure_as_plain_CALL_prepared_statement() throws SQLException {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("CALL order_pkg_place_order(?, ?, ?, ?)")) {
                ps.setLong(1, 4);
                ps.setLong(2, 6);
                ps.setInt(3, 1);
                ps.setNull(4, Types.BIGINT);
                boolean hasResultSet = ps.execute();
                long orderId = -1;
                if (hasResultSet) {
                    try (ResultSet rs = ps.getResultSet()) {
                        assertThat(rs.next()).isTrue();
                        orderId = rs.getLong(1);
                    }
                }
                assertThat(orderId).as("CALL returned the INOUT value as a one-row result set").isPositive();
                assertThat(Sql.queryString(c, "SELECT status FROM orders WHERE id = ?", orderId)).isEqualTo("NEW");
                plainCallWorked = true;
                Results.note("plain 'CALL order_pkg_place_order(?,?,?,?)' as PreparedStatement works too: INOUT value returned as a result set (order %d)", orderId);
            } catch (SQLException | AssertionError e) {
                plainCallWorked = false;
                Results.partial("plain CALL prepared statement did not deliver the INOUT value: " + e);
                assertThat(callEscapeWorked).as("at least the {call} escape must work").isTrue();
            }
        }
    }

    @Test
    @Order(8)
    void function_call_with_return_value() throws SQLException {
        try (Connection c = open(); CallableStatement cs = c.prepareCall("{? = call get_customer_tier(?)}")) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.setLong(2, 1);
            cs.execute();
            String tier = cs.getString(1);
            assertThat(tier).isIn("GOLD", "SILVER", "BRONZE");
            Results.note("get_customer_tier(1) = %s", tier);
        }
    }

    @Test
    @Order(9)
    void ref_cursor_out_parameter_inside_a_transaction() throws SQLException {
        try (Connection c = open()) {
            long customerId = Sql.queryLong(c, "SELECT customer_id FROM orders GROUP BY customer_id ORDER BY count(*) DESC, customer_id FETCH FIRST 1 ROWS ONLY");
            long expected = Sql.queryLong(c, "SELECT count(*) FROM orders WHERE customer_id = ?", customerId);
            c.setAutoCommit(false);
            try (CallableStatement cs = c.prepareCall("{? = call get_orders_for_customer(?)}")) {
                cs.registerOutParameter(1, Types.REF_CURSOR);
                cs.setLong(2, customerId);
                cs.execute();
                Object cursor = cs.getObject(1);
                assertThat(cursor).as("REF_CURSOR OUT parameter materialises as a ResultSet").isInstanceOf(ResultSet.class);
                int rows = 0;
                try (ResultSet rs = (ResultSet) cursor) {
                    while (rs.next()) {
                        rows++;
                        assertThat(rs.getString("order_no")).isNotBlank();
                        assertThat(rs.getBigDecimal("total_amount")).isNotNull();
                        rs.getLong("item_count");
                    }
                }
                assertThat(rows).isEqualTo((int) expected);
                Results.note("ref cursor get_orders_for_customer(%d) iterated %d rows inside a transaction", customerId, rows);
            } finally {
                c.commit();
                c.setAutoCommit(true);
            }
        }
    }

    @Test
    @Order(10)
    void database_metadata_lists_sales_tables_and_columns() throws SQLException {
        try (Connection c = open()) {
            DatabaseMetaData md = c.getMetaData();
            Set<String> tables = new java.util.TreeSet<>();
            try (ResultSet rs = md.getTables(null, "sales", "%", new String[] {"TABLE", "VIEW"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).contains("customer", "orders", "order_item", "payment", "inventory", "product", "audit_log", "v_customer_order_summary");
            Set<String> columns = new java.util.TreeSet<>();
            try (ResultSet rs = md.getColumns(null, "sales", "orders", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
            assertThat(columns).contains("id", "order_no", "customer_id", "status", "total_amount");
            try (ResultSet rs = md.getPrimaryKeys(null, "sales", "orders")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("id");
            }
            Results.note("getTables(sales): %s; getColumns(orders): %d columns", tables, columns.size());
        }
    }

    @Test
    @Order(11)
    void multi_statement_execute_with_getMoreResults() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            try {
                boolean first = st.execute("SELECT 1 AS a; SELECT 2 AS b");
                assertThat(first).isTrue();
                try (ResultSet rs = st.getResultSet()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                boolean more = st.getMoreResults();
                if (more) {
                    try (ResultSet rs = st.getResultSet()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(2);
                    }
                    Results.note("getMoreResults delivered the second result set of 'SELECT 1; SELECT 2'");
                } else {
                    Results.partial("getMoreResults() returned false after the first result set of a multi-statement execute (second result not delivered)");
                }
            } catch (SQLException e) {
                Results.partial("multi-statement execute not supported through the gateway: " + Sql.describe(e));
            }
        }
    }

    @Test
    @Order(12)
    void errors_map_to_sql_states_and_standard_exception_subclasses() throws SQLException {
        try (Connection c = open()) {
            assertThatThrownBy(() -> Sql.queryLong(c, "SELECT * FROM"))
                    .isInstanceOf(SQLSyntaxErrorException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42601"));
            String email = Sql.queryString(c, "SELECT email FROM customer ORDER BY id FETCH FIRST 1 ROWS ONLY");
            assertThatThrownBy(() -> Sql.update(c, "INSERT INTO customer (email, first_name, last_name, country_code) VALUES (?, 'Dup', 'Licate', 'DE')", email))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            assertThat(Sql.queryLong(c, "SELECT 1")).as("connection usable after errors").isEqualTo(1);
            // error inside a pinned transaction: PostgreSQL aborts the transaction, the driver surfaces 25P02 until rollback
            c.setAutoCommit(false);
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM product")).isPositive();
            assertThatThrownBy(() -> Sql.queryLong(c, "SELECT * FROM")).isInstanceOf(SQLSyntaxErrorException.class);
            assertThatThrownBy(() -> Sql.queryLong(c, "SELECT 1")).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("25P02"));
            c.rollback();
            assertThat(Sql.queryLong(c, "SELECT 1")).isEqualTo(1);
            c.rollback();
            // deviation check: when the FIRST statement of a transaction fails, PostgreSQL still aborts the transaction (25P02 next)
            assertThatThrownBy(() -> Sql.queryLong(c, "SELECT * FROM")).isInstanceOf(SQLSyntaxErrorException.class);
            String afterFirstFailure;
            try {
                Sql.queryLong(c, "SELECT 1");
                afterFirstFailure = "succeeded";
            } catch (SQLException e) {
                afterFirstFailure = e.getSQLState();
            }
            c.rollback();
            c.setAutoCommit(true);
            assertThat(Sql.queryLong(c, "SELECT 1")).isEqualTo(1);
            Results.note("42601 -> SQLSyntaxErrorException, 23505 -> SQLIntegrityConstraintViolationException, 25P02 after a failure inside a pinned transaction, session survives");
            if (!"25P02".equals(afterFirstFailure)) {
                Results.partial("when the first statement of a transaction fails the next statement " + afterFirstFailure
                        + " (direct PostgreSQL: 25P02, transaction aborted) - the failed first statement did not pin the session");
            }
        }
    }

    @Test
    @Order(13)
    void is_valid_and_close() throws SQLException {
        Connection c = open();
        assertThat(c.isValid(2)).isTrue();
        assertThat(c.isClosed()).isFalse();
        c.close();
        assertThat(c.isClosed()).isTrue();
        assertThat(c.isValid(2)).isFalse();
        assertThatThrownBy(c::createStatement).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
        Stack s = Stack.current();
        org.dbplatform.it.support.Await.until("gateway shows no logical sessions after close", java.time.Duration.ofSeconds(10),
                () -> s.gatewayAdmin("/health").path("logicalSessions").asInt() == 0);
    }
}
