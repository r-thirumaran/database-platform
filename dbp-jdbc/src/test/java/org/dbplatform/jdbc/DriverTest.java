package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Hello;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverTest extends GatewayTest {

    @Test
    void registeredWithDriverManager() throws Exception {
        Driver d = DriverManager.getDriver("jdbc:dbp://localhost/sales");
        assertThat(d).isInstanceOf(DbpDriver.class);
        assertThat(DbpDriver.isRegistered()).isTrue();
    }

    @Test
    void discoverableThroughServiceLoader() {
        boolean found = false;
        for (Driver d : ServiceLoader.load(Driver.class)) {
            if (d instanceof DbpDriver) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void acceptsOnlyDbpUrls() throws Exception {
        DbpDriver d = DbpDriver.instance();
        assertThat(d.acceptsURL("jdbc:dbp://host/ds")).isTrue();
        assertThat(d.acceptsURL("JDBC:DBP://host/ds")).isTrue();
        assertThat(d.acceptsURL("jdbc:h2:mem:x")).isFalse();
        assertThat(d.acceptsURL(null)).isFalse();
        assertThat(d.connect("jdbc:h2:mem:x", new Properties())).isNull();
    }

    @Test
    void malformedUrlIsReportedAs08001() {
        assertThatThrownBy(() -> DbpDriver.instance().connect("jdbc:dbp://host", new Properties()))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"))
                .hasMessageContaining("invalid DBP JDBC URL");
    }

    @Test
    void propertyInfoReflectsUrlAndDefaults() {
        Properties info = new Properties();
        info.setProperty("application", "orders");
        DriverPropertyInfo[] props = DbpDriver.instance().getPropertyInfo("jdbc:dbp://h/ds?apiKey=abc&fetchSize=7", info);
        assertThat(props).extracting(p -> p.name).contains("apiKey", "application", "user", "password", "ssl",
                "connectTimeoutMs", "socketTimeoutMs", "fetchSize", "maxFrameBytes", "autoCommit", "readOnly", "schema",
                "txIsolation");
        assertThat(props).filteredOn(p -> p.name.equals("apiKey")).extracting(p -> p.value).containsExactly("abc");
        assertThat(props).filteredOn(p -> p.name.equals("fetchSize")).extracting(p -> p.value).containsExactly("7");
        assertThat(props).filteredOn(p -> p.name.equals("application")).extracting(p -> p.value).containsExactly("orders");
        assertThat(props).filteredOn(p -> p.name.equals("connectTimeoutMs")).extracting(p -> p.value).containsExactly("10000");
    }

    @Test
    void versionAndCompliance() {
        DbpDriver d = DbpDriver.instance();
        assertThat(d.getMajorVersion()).isEqualTo(0);
        assertThat(d.getMinorVersion()).isEqualTo(1);
        assertThat(d.jdbcCompliant()).isFalse();
        assertThat(d.getParentLogger().getName()).isEqualTo("org.dbplatform.jdbc");
        assertThat(DriverVersion.VERSION).isEqualTo("0.1.0-SNAPSHOT");
    }

    @Test
    void connectsThroughDriverManager() throws Exception {
        try (Connection c = DriverManager.getConnection(gateway.url("sales", "apiKey=k1"))) {
            assertThat(c).isInstanceOf(DbpConnection.class);
            assertThat(c.isValid(1)).isTrue();
            Hello hello = gateway.last(Hello.class);
            assertThat(hello.clientName()).isEqualTo("dbp-jdbc");
            assertThat(hello.clientVersion()).isEqualTo("0.1.0-SNAPSHOT");
            assertThat(hello.protocolVersion()).isEqualTo(1);
        }
    }
}
