package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.Hello;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceTest extends GatewayTest {

    @Test
    void beanPropertiesBuildTheUrlAndHello() throws Exception {
        DbpDataSource ds = new DbpDataSource();
        ds.setHost(gateway.host());
        ds.setPort(gateway.port());
        ds.setDatasource("sales");
        ds.setApiKey("dbp_1_key");
        ds.setApplication("orders");
        ds.setFetchSize(7);
        ds.setConnectTimeoutMs(2000);
        ds.setSocketTimeoutMs(0);
        ds.setSsl(false);
        assertThat(ds.getEffectiveUrl()).isEqualTo(gateway.url("sales"));
        assertThat(ds.getFetchSize()).isEqualTo(7);
        assertThat(ds.isSsl()).isFalse();
        assertThat(ds.getParentLogger().getName()).isEqualTo("org.dbplatform.jdbc");
        assertThat(ds.isWrapperFor(DbpDataSource.class)).isTrue();
        assertThat(ds.unwrap(DbpDataSource.class)).isSameAs(ds);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            Map<String, String> p = gateway.last(Hello.class).properties();
            assertThat(p).containsEntry("datasource", "sales").containsEntry("apiKey", "dbp_1_key")
                    .containsEntry("application", "orders").doesNotContainKey("user");
            s.executeQuery("select 1 rows");
            assertThat(gateway.last(Execute.class).options().fetchSize()).isEqualTo(7);
        }
    }

    @Test
    void userAndPasswordFollowTheDriverMapping() throws Exception {
        DbpDataSource ds = new DbpDataSource(gateway.url("sales"));
        ds.setUser("alice");
        ds.setPassword("dbp_2_secret");
        try (Connection c = ds.getConnection()) {
            Map<String, String> p = gateway.last(Hello.class).properties();
            assertThat(p).containsEntry("user", "alice").containsEntry("apiKey", "dbp_2_secret");
        }
        try (Connection c = ds.getConnection("bob", "dbp_3_other")) {
            Map<String, String> p = gateway.last(Hello.class).properties();
            assertThat(p).containsEntry("user", "bob").containsEntry("apiKey", "dbp_3_other");
        }
        ds.setApiKey("explicit");
        try (Connection c = ds.getConnection()) {
            assertThat(gateway.last(Hello.class).properties()).containsEntry("apiKey", "explicit");
        }
    }

    @Test
    void missingConfigurationIsReported() {
        DbpDataSource ds = new DbpDataSource();
        ds.setHost("localhost");
        assertThatThrownBy(ds::getConnection)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));
        ds.setUrl("jdbc:h2:mem:x");
        assertThatThrownBy(ds::getConnection).hasMessageContaining("not a DBP JDBC URL");
        assertThat(ds.toString()).contains("jdbc:h2:mem:x");
        ds.setLoginTimeout(3);
        assertThat(ds.getLoginTimeout()).isEqualTo(3);
        assertThat(ds.getLogWriter()).isNull();
    }
}
