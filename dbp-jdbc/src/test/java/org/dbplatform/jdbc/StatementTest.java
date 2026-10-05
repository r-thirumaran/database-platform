package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.Fetch;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementTest extends GatewayTest {

    @Test
    void executeQueryReturnsRowsAndMetadata() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select id, name from customers");
            Execute e = gateway.last(Execute.class);
            assertThat(e.isDirect()).isTrue();
            assertThat(e.sql()).isEqualTo("select id, name from customers");
            assertThat(e.kind().name()).isEqualTo("STATEMENT");
            assertThat(e.options().expect()).isEqualTo(Execute.Expect.QUERY);
            assertThat(e.options().fetchSize()).isEqualTo(100);

            ResultSetMetaData md = rs.getMetaData();
            assertThat(md.getColumnCount()).isEqualTo(2);
            assertThat(md.getColumnLabel(2)).isEqualTo("NAME");
            assertThat(md.getColumnName(2)).isEqualTo("CUSTOMER_NAME");
            assertThat(md.getColumnType(1)).isEqualTo(Types.INTEGER);
            assertThat(md.getColumnClassName(1)).isEqualTo("java.lang.Integer");
            assertThat(md.getTableName(1)).isEqualTo("CUSTOMERS");
            assertThat(md.isAutoIncrement(1)).isTrue();
            assertThat(md.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);

            assertThat(rs.isBeforeFirst()).isTrue();
            assertThat(rs.getRow()).isZero();
            assertThat(rs.next()).isTrue();
            assertThat(rs.isFirst()).isTrue();
            assertThat(rs.getRow()).isEqualTo(1);
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString("name")).isEqualTo("alice");
            assertThat(rs.getString("customer_name")).isEqualTo("alice");
            assertThat(rs.next()).isTrue();
            assertThat(rs.next()).isTrue();
            assertThat(rs.isLast()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("carol");
            assertThat(rs.next()).isFalse();
            assertThat(rs.isAfterLast()).isTrue();
            assertThat(rs.next()).isFalse();
            assertThat(rs.getStatement()).isSameAs(s);
            assertThat(rs.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
            assertThat(rs.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
            rs.close();
            assertThat(gateway.received(CloseCursor.class)).isEmpty();
        }
    }

    @Test
    void fetchSizePagesThroughTheCursor() throws Exception {
        try (Connection c = connect("apiKey=k1&fetchSize=2"); Statement s = c.createStatement()) {
            assertThat(s.getFetchSize()).isEqualTo(2);
            ResultSet rs = s.executeQuery("select 5 rows");
            assertThat(gateway.last(Execute.class).options().fetchSize()).isEqualTo(2);
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
            assertThat(ids).containsExactly(1, 2, 3, 4, 5);
            List<Fetch> fetches = gateway.received(Fetch.class);
            assertThat(fetches).hasSize(2);
            assertThat(fetches).allSatisfy(f -> assertThat(f.maxRows()).isEqualTo(2));
            int cursorId = fetches.get(0).cursorId();
            assertThat(fetches).allSatisfy(f -> assertThat(f.cursorId()).isEqualTo(cursorId));
            rs.close();
            // the last batch was flagged last: the server closed the cursor, no CLOSE_CURSOR must follow
            assertThat(gateway.received(CloseCursor.class)).isEmpty();
        }
    }

    @Test
    void closingAnUnfinishedCursorSendsCloseCursor() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.setFetchSize(2);
            ResultSet rs = s.executeQuery("select 5 rows");
            assertThat(rs.next()).isTrue();
            rs.close();
            rs.close();
            assertThat(gateway.received(CloseCursor.class)).hasSize(1);
            assertThat(gateway.sessions().get(0).cursors).isEmpty();
            assertThatThrownBy(rs::next).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
        }
    }

    @Test
    void maxRowsIsSentAndEnforced() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.setMaxRows(2);
            assertThat(s.getMaxRows()).isEqualTo(2);
            ResultSet rs = s.executeQuery("select 5 rows");
            assertThat(gateway.last(Execute.class).options().maxRows()).isEqualTo(2);
            int n = 0;
            while (rs.next()) {
                n++;
            }
            assertThat(n).isEqualTo(2);
            s.setQueryTimeout(7);
            s.executeQuery("select 1 rows");
            assertThat(gateway.last(Execute.class).options().queryTimeoutSeconds()).isEqualTo(7);
        }
    }

    @Test
    void updatesAndExecute() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("update customers set name = 'x'")).isEqualTo(1);
            assertThat(gateway.last(Execute.class).options().expect()).isEqualTo(Execute.Expect.UPDATE);
            assertThat(s.executeLargeUpdate("delete from customers")).isEqualTo(1L);
            assertThat(s.executeUpdate("create table t (id int)")).isZero();

            assertThat(s.execute("insert into customers values (1)")).isFalse();
            assertThat(gateway.last(Execute.class).options().expect()).isEqualTo(Execute.Expect.ANY);
            assertThat(s.getResultSet()).isNull();
            assertThat(s.getUpdateCount()).isEqualTo(1);
            assertThat(s.getMoreResults()).isFalse();
            assertThat(s.getUpdateCount()).isEqualTo(-1);

            assertThat(s.execute("select 2 rows")).isTrue();
            assertThat(s.getUpdateCount()).isEqualTo(-1);
            assertThat(s.getResultSet()).isNotNull();
        }
    }

    @Test
    void getMoreResultsFollowsTheItemOrder() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(s.execute("select multi")).isTrue();
            ResultSet first = s.getResultSet();
            assertThat(first.next()).isTrue();
            assertThat(first.next()).isFalse();

            assertThat(s.getMoreResults()).isFalse();
            assertThat(first.isClosed()).isTrue();
            assertThat(s.getUpdateCount()).isEqualTo(5);
            assertThat(s.getResultSet()).isNull();

            assertThat(s.getMoreResults()).isTrue();
            ResultSet second = s.getResultSet();
            assertThat(second.next()).isTrue();
            assertThat(second.next()).isTrue();
            assertThat(second.next()).isFalse();

            assertThat(s.getMoreResults(Statement.KEEP_CURRENT_RESULT)).isFalse();
            assertThat(second.isClosed()).isFalse();
            assertThat(s.getUpdateCount()).isEqualTo(-1);
            assertThat(s.getMoreResults()).isFalse();
        }
    }

    @Test
    void generatedKeys() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("insert into orders values (1)", Statement.RETURN_GENERATED_KEYS)).isEqualTo(1);
            assertThat(gateway.last(Execute.class).options().autoGeneratedKeys()).isEqualTo(Statement.RETURN_GENERATED_KEYS);
            ResultSet keys = s.getGeneratedKeys();
            assertThat(keys.getMetaData().getColumnLabel(1)).isEqualTo("ID");
            assertThat(keys.next()).isTrue();
            assertThat(keys.getLong(1)).isEqualTo(101L);
            assertThat(keys.next()).isFalse();
            keys.close();

            s.execute("insert into orders values (2)", new String[] {"ORDER_ID"});
            assertThat(gateway.last(Execute.class).options().generatedKeyColumns()).containsExactly("ORDER_ID");
            keys = s.getGeneratedKeys();
            assertThat(keys.getMetaData().getColumnLabel(1)).isEqualTo("ORDER_ID");
            assertThat(keys.next()).isTrue();

            s.executeUpdate("insert into orders values (3)");
            ResultSet none = s.getGeneratedKeys();
            assertThat(none.getMetaData().getColumnCount()).isZero();
            assertThat(none.next()).isFalse();

            assertThatThrownBy(() -> s.executeUpdate("insert", new int[] {1})).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> s.executeUpdate("insert", 42)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void statementBatch() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(s.executeBatch()).isEmpty();
            s.addBatch("insert into t values (1)");
            s.addBatch("insert into t values (2)");
            s.addBatch("update t set x = 1");
            assertThat(s.executeBatch()).containsExactly(1, 1, 1);
            ExecuteBatch b = gateway.last(ExecuteBatch.class);
            assertThat(b.statementId()).isEqualTo(-1);
            assertThat(b.sqls()).containsExactly("insert into t values (1)", "insert into t values (2)", "update t set x = 1");
            assertThat(b.paramSets()).isEmpty();
            assertThat(s.executeBatch()).isEmpty();

            s.addBatch("insert into t values (3)");
            s.clearBatch();
            assertThat(s.executeLargeBatch()).isEmpty();

            s.addBatch("insert into t values (4)");
            s.addBatch("insert into missing values (5)");
            assertThatThrownBy(s::executeBatch)
                    .isInstanceOf(BatchUpdateException.class)
                    .satisfies(e -> {
                        BatchUpdateException bue = (BatchUpdateException) e;
                        assertThat(bue.getSQLState()).isEqualTo("42S02");
                        assertThat(bue.getUpdateCounts()).isEmpty();
                        assertThat(bue.getCause()).isInstanceOf(SQLSyntaxErrorException.class);
                    });
            // the batch is cleared after a failure
            assertThat(s.executeBatch()).isEmpty();
        }
    }

    @Test
    void errorsAreMappedToSqlExceptionSubclasses() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("select * from missing"))
                    .isInstanceOf(SQLSyntaxErrorException.class)
                    .satisfies(e -> {
                        assertThat(((SQLException) e).getSQLState()).isEqualTo("42S02");
                        assertThat(((SQLException) e).getErrorCode()).isEqualTo(42102);
                    })
                    .hasMessageContaining("MISSING");
            assertThat(c.isClosed()).isFalse();
            assertThatThrownBy(() -> s.executeQuery("update t set x = 1"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07005"));
            assertThatThrownBy(() -> s.executeUpdate("select 1 rows"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07005"));
            // still usable afterwards
            assertThat(s.executeQuery("select 1 rows").next()).isTrue();
        }
    }

    @Test
    void warningsAndCancel() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select warn");
            assertThat((Object) s.getWarnings()).isNotNull();
            assertThat(s.getWarnings().getMessage()).isEqualTo("query warning");
            assertThat(s.getWarnings().getErrorCode()).isEqualTo(7);
            assertThat((Object) rs.getWarnings()).isNull();
            s.clearWarnings();
            assertThat((Object) s.getWarnings()).isNull();

            s.cancel();
            assertThat(s.getWarnings().getSQLState()).isEqualTo("01000");
            assertThat(s.getWarnings().getMessage()).contains("cancel");
            s.clearWarnings();

            s.executeUpdate("update t set x = 1 warn");
            assertThat(s.getWarnings().getMessage()).isEqualTo("update warning");
        }
    }

    @Test
    void lifecycle() throws Exception {
        Connection c = connect();
        Statement s = c.createStatement();
        assertThat(s.getConnection()).isSameAs(c);
        assertThat(s.isPoolable()).isFalse();
        s.setPoolable(true);
        assertThat(s.isPoolable()).isTrue();
        s.setEscapeProcessing(false);
        s.setFetchSize(0);
        assertThat(s.getFetchSize()).isEqualTo(100);
        assertThatThrownBy(() -> s.setFetchSize(-1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> s.setMaxRows(-1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> s.setCursorName("c")).isInstanceOf(SQLFeatureNotSupportedException.class);

        s.setFetchSize(2);
        ResultSet rs = s.executeQuery("select 5 rows");
        s.close();
        s.close();
        assertThat(s.isClosed()).isTrue();
        assertThat(rs.isClosed()).isTrue();
        assertThat(gateway.received(CloseCursor.class)).hasSize(1);
        assertThatThrownBy(() -> s.executeQuery("select 1 rows"))
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("HY010"));

        Statement s2 = c.createStatement();
        s2.closeOnCompletion();
        assertThat(s2.isCloseOnCompletion()).isTrue();
        ResultSet rs2 = s2.executeQuery("select 1 rows");
        // re-executing closes the previous result set but must not close the statement
        ResultSet rs3 = s2.executeQuery("select 1 rows");
        assertThat(rs2.isClosed()).isTrue();
        assertThat(s2.isClosed()).isFalse();
        rs3.close();
        assertThat(s2.isClosed()).isTrue();
        c.close();
    }

    @Test
    void toStringIsSafe() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select 1 rows");
            assertThat(s.toString()).contains("DbpStatement");
            assertThat(rs.toString()).contains("DbpResultSet");
            assertThat(rs.getMetaData().toString()).contains("2 columns");
        }
    }
}
