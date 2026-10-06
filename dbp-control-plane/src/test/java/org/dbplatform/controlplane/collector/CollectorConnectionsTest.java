package org.dbplatform.controlplane.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.junit.jupiter.api.Test;

/** Collector connections inherit connection-level {@code jdbcProperties} only, never the gateway's session settings (defect D2). */
class CollectorConnectionsTest {

    private static DatabaseInstance db(Enums.Engine engine, Map<String, String> jdbcProperties) {
        DatabaseInstance d = new DatabaseInstance();
        d.setId("db-1"); d.setName("test"); d.setEngine(engine); d.setHost("h"); d.setPort(1);
        d.setJdbcProperties(new LinkedHashMap<>(jdbcProperties));
        return d;
    }

    @Test
    void postgresCollectorConnectionDropsSessionSettingsAndKeepsConnectionSettings() {
        Map<String, String> gatewayProperties = new LinkedHashMap<>();
        gatewayProperties.put("currentSchema", "sales");
        gatewayProperties.put("escapeSyntaxCallMode", "callIfNoReturn");
        gatewayProperties.put("stringtype", "unspecified");
        gatewayProperties.put("readOnlyMode", "always");
        gatewayProperties.put("ApplicationName", "dbp-gateway");
        gatewayProperties.put("sslmode", "verify-full");
        gatewayProperties.put("sslrootcert", "/etc/ssl/ca.pem");
        gatewayProperties.put("connectTimeout", "3");
        gatewayProperties.put("socketTimeout", "45");
        gatewayProperties.put("loginTimeout", "4");
        Properties p = CollectorConnections.properties(db(Enums.Engine.POSTGRES, gatewayProperties), "dbp_collector", "secret", 10);

        assertThat(p.stringPropertyNames()).doesNotContain("currentSchema", "escapeSyntaxCallMode", "stringtype", "readOnlyMode");
        assertThat(p.getProperty("ApplicationName")).isEqualTo("dbp-collector");
        assertThat(p.getProperty("sslmode")).isEqualTo("verify-full");
        assertThat(p.getProperty("sslrootcert")).isEqualTo("/etc/ssl/ca.pem");
        assertThat(p.getProperty("connectTimeout")).isEqualTo("3");   // explicit value wins over the default
        assertThat(p.getProperty("socketTimeout")).isEqualTo("45");
        assertThat(p.getProperty("loginTimeout")).isEqualTo("4");
        assertThat(p.getProperty("user")).isEqualTo("dbp_collector");
        assertThat(p.getProperty("password")).isEqualTo("secret");
    }

    @Test
    void defaultsApplyWhenTheDatabaseHasNoJdbcProperties() {
        Properties p = CollectorConnections.properties(db(Enums.Engine.POSTGRES, Map.of()), null, null, 10);
        assertThat(p.getProperty("connectTimeout")).isEqualTo("10");
        assertThat(p.getProperty("socketTimeout")).isEqualTo("60");
        assertThat(p.getProperty("ApplicationName")).isEqualTo("dbp-collector");
        assertThat(p.stringPropertyNames()).doesNotContain("user", "password");
    }

    @Test
    void oracleAndSqlServerKeepTheirTlsAndTimeoutSettings() {
        Properties ora = CollectorConnections.properties(db(Enums.Engine.ORACLE, Map.of(
                "oracle.net.ssl_server_dn_match", "true", "oracle.jdbc.ReadTimeout", "60000", "v$session.program", "dbp-gateway", "oracle.jdbc.defaultRowPrefetch", "500")), "c", "s", 10);
        assertThat(ora.getProperty("oracle.net.ssl_server_dn_match")).isEqualTo("true");
        assertThat(ora.getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("60000");
        assertThat(ora.getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("10000");
        assertThat(ora.getProperty("v$session.program")).isEqualTo("dbp-collector");
        assertThat(ora.stringPropertyNames()).doesNotContain("oracle.jdbc.defaultRowPrefetch");

        Properties ms = CollectorConnections.properties(db(Enums.Engine.MSSQL, Map.of(
                "encrypt", "true", "trustServerCertificate", "true", "applicationName", "dbp-gateway", "selectMethod", "cursor")), "c", "s", 10);
        assertThat(ms.getProperty("encrypt")).isEqualTo("true");
        assertThat(ms.getProperty("trustServerCertificate")).isEqualTo("true");
        assertThat(ms.getProperty("loginTimeout")).isEqualTo("10");
        assertThat(ms.getProperty("applicationName")).isEqualTo("dbp-collector");
        assertThat(ms.stringPropertyNames()).doesNotContain("selectMethod");
    }

    @Test
    void everyEngineIsHandled() {
        for (Enums.Engine engine : Enums.Engine.values()) {
            assertThat(CollectorConnections.properties(db(engine, Map.of("url", "jdbc:x:y", "sslmode", "require")), "u", "p", 5).getProperty("sslmode")).isEqualTo("require");
        }
    }
}
