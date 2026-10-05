package org.dbplatform.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Base class: a fresh {@link FakeGateway} per test. */
abstract class GatewayTest {

    protected FakeGateway gateway;

    @BeforeEach
    void startGateway() throws IOException {
        gateway = FakeGateway.start();
    }

    @AfterEach
    void stopGateway() {
        if (gateway != null) {
            gateway.close();
        }
    }

    protected Connection connect() throws SQLException {
        return connect("apiKey=k1");
    }

    protected Connection connect(String query) throws SQLException {
        return DriverManager.getConnection(gateway.url("sales", query));
    }

    protected Connection connect(String query, Properties props) throws SQLException {
        return DriverManager.getConnection(gateway.url("sales", query), props);
    }
}
