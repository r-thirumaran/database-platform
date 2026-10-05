package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Close;
import org.dbplatform.protocol.messages.Commit;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.Ping;
import org.dbplatform.protocol.messages.ReleaseSavepoint;
import org.dbplatform.protocol.messages.Rollback;
import org.dbplatform.protocol.messages.SetAutoCommit;
import org.dbplatform.protocol.messages.SetCatalog;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.SetNetworkTimeout;
import org.dbplatform.protocol.messages.SetReadOnly;
import org.dbplatform.protocol.messages.SetSavepoint;
import org.dbplatform.protocol.messages.SetSchema;
import org.dbplatform.protocol.messages.SetTransactionIsolation;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionTest extends GatewayTest {

    @Test
    void helloCarriesAllSessionProperties() throws Exception {
        Properties info = new Properties();
        info.setProperty("user", "alice");
        info.setProperty("password", "pw-ignored-because-apiKey-present");
        info.setProperty("apiKey", "from-properties");
        info.setProperty("clientInfo.ClientUser", "svc");
        String query = "apiKey=from-url&application=orders&autoCommit=false&readOnly=true&schema=SALES"
                + "&clientInfo.ApplicationName=orders-service&txIsolation=8";
        try (Connection c = connect(query, info)) {
            Map<String, String> p = gateway.last(Hello.class).properties();
            assertThat(p).containsEntry("datasource", "sales")
                    .containsEntry("apiKey", "from-url")
                    .containsEntry("application", "orders")
                    .containsEntry("user", "alice")
                    .containsEntry("autoCommit", "false")
                    .containsEntry("readOnly", "true")
                    .containsEntry("schema", "SALES")
                    .containsEntry("clientInfo.ApplicationName", "orders-service")
                    .containsEntry("clientInfo.ClientUser", "svc")
                    .containsEntry("txIsolation", "8");
            assertThat(p).doesNotContainKey("password");
            assertThat(c.getAutoCommit()).isFalse();
            assertThat(c.isReadOnly()).isTrue();
            assertThat(c.getSchema()).isEqualTo("SALES");
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            assertThat(c.getClientInfo("ApplicationName")).isEqualTo("orders-service");
            assertThat(c.getClientInfo().getProperty("ClientUser")).isEqualTo("svc");
        }
    }

    @Test
    void passwordBecomesApiKeyWhenAbsent() throws Exception {
        try (Connection c = DriverManager.getConnection(gateway.url("sales"), "svc-user", "dbp_1_secret")) {
            Map<String, String> p = gateway.last(Hello.class).properties();
            assertThat(p).containsEntry("apiKey", "dbp_1_secret").containsEntry("user", "svc-user");
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    void helloWithoutApiKeyWhenNothingGiven() throws Exception {
        try (Connection c = DriverManager.getConnection(gateway.url("sales"))) {
            assertThat(gateway.last(Hello.class).properties()).doesNotContainKey("apiKey").containsEntry("autoCommit", "true");
        }
    }

    @Test
    void rejectedHelloSurfacesAsConnectionException() {
        assertThatThrownBy(() -> connect("apiKey=bad-key"))
                .isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08004"))
                .hasMessageContaining("invalid api key");
    }

    @Test
    void invalidPropertyValueIsRejected() {
        assertThatThrownBy(() -> connect("apiKey=k&fetchSize=lots"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"))
                .hasMessageContaining("fetchSize");
    }

    @Test
    void transactionFrames() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            c.setAutoCommit(false);
            assertThat(gateway.received(SetAutoCommit.class)).hasSize(1);
            assertThat(gateway.received(SetAutoCommit.class).get(0).autoCommit()).isFalse();
            assertThat(c.getAutoCommit()).isFalse();

            c.commit();
            assertThat(gateway.received(Commit.class)).hasSize(1);
            c.rollback();
            assertThat(gateway.last(Rollback.class).isFull()).isTrue();

            Savepoint unnamed = c.setSavepoint();
            assertThat(gateway.last(SetSavepoint.class).name()).isNull();
            assertThat(unnamed.getSavepointId()).isEqualTo(1);
            assertThatThrownBy(unnamed::getSavepointName).isInstanceOf(SQLException.class);

            Savepoint named = c.setSavepoint("before_update");
            assertThat(gateway.last(SetSavepoint.class).name()).isEqualTo("before_update");
            assertThat(named.getSavepointName()).isEqualTo("before_update");
            assertThatThrownBy(named::getSavepointId).isInstanceOf(SQLException.class);

            c.rollback(named);
            assertThat(gateway.last(Rollback.class).savepointName()).isEqualTo("before_update");
            c.releaseSavepoint(unnamed);
            assertThat(gateway.last(ReleaseSavepoint.class).name()).isEqualTo("SP_1");

            c.setAutoCommit(true);
            assertThat(gateway.last(SetAutoCommit.class).autoCommit()).isTrue();
        }
    }

    @Test
    void sessionSettingFrames() throws Exception {
        try (Connection c = connect()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertThat(gateway.last(SetTransactionIsolation.class).level()).isEqualTo(8);
            assertThat(c.getTransactionIsolation()).isEqualTo(8);
            assertThatThrownBy(() -> c.setTransactionIsolation(Connection.TRANSACTION_NONE)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> c.setTransactionIsolation(3)).isInstanceOf(SQLException.class);

            c.setReadOnly(true);
            assertThat(gateway.last(SetReadOnly.class).readOnly()).isTrue();
            assertThat(c.isReadOnly()).isTrue();

            c.setSchema("HR");
            assertThat(gateway.last(SetSchema.class).schema()).isEqualTo("HR");
            assertThat(c.getSchema()).isEqualTo("HR");

            c.setCatalog("MAIN");
            assertThat(gateway.last(SetCatalog.class).catalog()).isEqualTo("MAIN");
            assertThat(c.getCatalog()).isEqualTo("MAIN");

            c.setNetworkTimeout(null, 5000);
            assertThat(gateway.last(SetNetworkTimeout.class).millis()).isEqualTo(5000);
            assertThat(c.getNetworkTimeout()).isEqualTo(5000);
            assertThat(c.nativeSQL("select 1")).isEqualTo("select 1");
            assertThat(c.getHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            assertThat(c.getTypeMap()).isEmpty();
        }
    }

    @Test
    void clientInfo() throws Exception {
        try (Connection c = connect()) {
            c.setClientInfo("ApplicationName", "orders");
            SetClientInfo ci = gateway.last(SetClientInfo.class);
            assertThat(ci.name()).isEqualTo("ApplicationName");
            assertThat(ci.value()).isEqualTo("orders");
            assertThat(c.getClientInfo("ApplicationName")).isEqualTo("orders");

            assertThatThrownBy(() -> c.setClientInfo("fail", "x"))
                    .isInstanceOf(SQLClientInfoException.class)
                    .satisfies(e -> assertThat(((SQLClientInfoException) e).getFailedProperties()).containsKey("fail"));

            Properties props = new Properties();
            props.setProperty("ClientUser", "u");
            props.setProperty("fail", "y");
            assertThatThrownBy(() -> c.setClientInfo(props))
                    .isInstanceOf(SQLClientInfoException.class)
                    .satisfies(e -> assertThat(((SQLClientInfoException) e).getFailedProperties()).containsOnlyKeys("fail"));
            assertThat(c.getClientInfo("ClientUser")).isEqualTo("u");

            c.setClientInfo("ApplicationName", null);
            assertThat(c.getClientInfo("ApplicationName")).isNull();
        }
    }

    @Test
    void isValidUsesPing() throws Exception {
        Connection c = connect();
        assertThat(c.isValid(2)).isTrue();
        assertThat(gateway.received(Ping.class)).hasSize(1);
        assertThatThrownBy(() -> c.isValid(-1)).isInstanceOf(SQLException.class);
        c.close();
        assertThat(c.isValid(1)).isFalse();
    }

    @Test
    void closeIsIdempotentAndSendsCloseOnce() throws Exception {
        Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select 1 rows");
        c.close();
        c.close();
        assertThat(gateway.received(Close.class)).hasSize(1);
        assertThat(c.isClosed()).isTrue();
        assertThat(s.isClosed()).isTrue();
        assertThat(rs.isClosed()).isTrue();
        assertThatThrownBy(c::createStatement)
                .isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
        assertThatThrownBy(() -> s.executeQuery("select 1 rows")).isInstanceOf(SQLException.class);
    }

    @Test
    void abortClosesWithoutFrames() throws Exception {
        Connection c = connect();
        gateway.clearReceived();
        c.abort(Runnable::run);
        assertThat(c.isClosed()).isTrue();
        assertThat(gateway.received()).isEmpty();
    }

    @Test
    void resultSetTypeNegotiation() throws Exception {
        try (Connection c = connect()) {
            Statement s = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
            assertThat(s.getResultSetType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
            SQLWarning w = c.getWarnings();
            assertThat((Object) w).isNotNull();
            assertThat(w.getSQLState()).isEqualTo("01S02");
            assertThat(w.getMessage()).contains("TYPE_SCROLL_INSENSITIVE");
            c.clearWarnings();
            assertThat((Object) c.getWarnings()).isNull();

            assertThatThrownBy(() -> c.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE))
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("0A000"));
            assertThatThrownBy(() -> c.prepareStatement("select 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);

            Statement h = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.HOLD_CURSORS_OVER_COMMIT);
            assertThat(h.getResultSetHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            assertThat(c.getWarnings().getMessage()).contains("HOLD_CURSORS_OVER_COMMIT");
        }
    }

    @Test
    void unsupportedFactories() throws Exception {
        try (Connection c = connect()) {
            assertThatThrownBy(() -> c.createArrayOf("INTEGER", new Object[] {1}))
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("0A000"));
            assertThatThrownBy(() -> c.createStruct("T", new Object[0])).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(c::createSQLXML).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> c.prepareStatement("insert", new int[] {1})).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThat(c.createBlob().length()).isZero();
            assertThat(c.createClob().length()).isZero();
            assertThat(c.createNClob().length()).isZero();
        }
    }

    @Test
    void wrapperOnlyForOwnTypes() throws Exception {
        try (Connection c = connect()) {
            assertThat(c.isWrapperFor(Connection.class)).isTrue();
            assertThat(c.isWrapperFor(DbpConnection.class)).isTrue();
            assertThat(c.unwrap(DbpConnection.class)).isSameAs(c);
            assertThat(c.isWrapperFor(Runnable.class)).isFalse();
            assertThatThrownBy(() -> c.unwrap(Runnable.class)).isInstanceOf(SQLException.class);
            assertThat(c.toString()).contains("sales");
            assertThat(((DbpConnection) c).getSessionId()).startsWith("sess-");
        }
    }

    @Test
    void fatalErrorClosesTheConnection() throws Exception {
        Connection c = connect();
        Statement s = c.createStatement();
        assertThatThrownBy(() -> s.execute("KILL"))
                .isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08006"));
        assertThat(c.isClosed()).isTrue();
        assertThat(s.isClosed()).isTrue();
        assertThatThrownBy(c::createStatement)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
    }

    @Test
    void gatewayClosingTheSocketIsReportedAs08006() throws Exception {
        Connection c = connect();
        gateway.setHandler((req, session) -> {
            if (req instanceof org.dbplatform.protocol.messages.Execute) {
                session.closeAfterReply();
                return;
            }
            gateway.echo().handle(req, session);
        });
        Statement s = c.createStatement();
        assertThatThrownBy(() -> s.executeQuery("select 1 rows"))
                .isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08006"));
        assertThat(c.isClosed()).isTrue();
        assertThatThrownBy(() -> c.prepareStatement("select 1"))
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
        c.close();
    }

    @Test
    void protocolViolationIsAConnectionFailure() throws Exception {
        Connection c = connect();
        gateway.setHandler((req, session) -> {
            if (req instanceof Ping) {
                session.reply(new org.dbplatform.protocol.messages.Prepared(1, 0));
                return;
            }
            gateway.echo().handle(req, session);
        });
        assertThat(c.isValid(1)).isFalse();
        assertThat(c.isClosed()).isTrue();
    }
}
