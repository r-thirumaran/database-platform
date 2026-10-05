package org.dbplatform.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticConfigLoaderTest {
    static final String YAML = """
            proxyId: proxy-1
            listeners:
              - name: oracle-main
                engine: ORACLE
                port: 1521
                routes:
                  - match: sales
                    host: oracle
                    port: 1521
                    serviceName: FREEPDB1
                    rewriteServiceName: true
                defaultRoute:
                  host: oracle
                  port: 1521
              - name: postgres-main
                engine: POSTGRES
                maxConnections: 100
                routes:
                  - match: sales
                    datasource: sales-pg
                    host: postgres
                    port: 5432
                    serviceName: sales
                    rewriteServiceName: false
              - engine: MSSQL
                defaultRoute: { host: mssql, port: 1433, datasource: erp }
            applications:
              - name: orders-service
                identityRules:
                  serviceAliases: [orders-service]
                  programNames: ["JDBC Thin Client/orders"]
                  cidrs: ["10.20.0.0/16"]
              - name: reporting
                identityRules:
                  programs: ["sqlplus*"]
                  machines: ["report-*"]
                  applicationNames: ["reporting"]
            quotas:
              - application: orders-service
                datasource: sales
                maxProxyConnections: 20
            datasourceQuotas:
              - datasource: sales
                maxProxyConnections: 200
            """;

    @Test
    void loadsYamlWithDefaultsAndAliases() throws Exception {
        ProxyConfigDocument doc = StaticConfigLoader.parse(YAML);
        assertThat(doc.proxyId()).isEqualTo("proxy-1");
        assertThat(doc.listeners()).hasSize(3);
        ListenerConfig oracle = doc.listener("oracle-main");
        assertThat(oracle.engine()).isEqualTo(Engine.ORACLE);
        assertThat(oracle.maxConnectionsOrDefault()).isEqualTo(5000);
        RouteConfig sales = oracle.routes().get(0);
        assertThat(sales.datasource()).isEqualTo("sales");
        assertThat(sales.datasourceId()).isEqualTo("sales");
        assertThat(oracle.defaultRoute().match()).isNull();
        assertThat(oracle.defaultRoute().datasource()).isNull();

        ListenerConfig pg = doc.listener("postgres-main");
        assertThat(pg.port()).as("engine default port").isEqualTo(5432);
        assertThat(pg.maxConnections()).isEqualTo(100);
        assertThat(pg.routes().get(0).datasource()).isEqualTo("sales-pg");

        ListenerConfig mssql = doc.listener("mssql-1433");
        assertThat(mssql.engine()).isEqualTo(Engine.MSSQL);
        assertThat(mssql.defaultRoute().datasource()).isEqualTo("erp");

        assertThat(doc.applications().get(0).id()).isEqualTo("orders-service");
        IdentityRules reporting = doc.applications().get(1).identityRules();
        assertThat(reporting.programNames()).containsExactly("sqlplus*");
        assertThat(reporting.machinePatterns()).containsExactly("report-*");
        assertThat(reporting.pgApplicationNames()).containsExactly("reporting");

        assertThat(doc.quotas().get(0).applicationId()).isEqualTo("orders-service");
        assertThat(doc.quotas().get(0).datasourceId()).isEqualTo("sales");
        assertThat(doc.datasourceQuotas().get(0).datasourceId()).isEqualTo("sales");
    }

    @Test
    void rejectsRoutesWithoutHostOrMatchAndDuplicateListeners() {
        assertThatThrownBy(() -> StaticConfigLoader.parse("listeners:\n  - name: a\n    engine: ORACLE\n    routes:\n      - match: x\n        port: 1\n"))
                .hasMessageContaining("requires a host");
        assertThatThrownBy(() -> StaticConfigLoader.parse("listeners:\n  - name: a\n    engine: ORACLE\n    routes:\n      - host: h\n        port: 1\n"))
                .hasMessageContaining("needs a 'match'");
        assertThatThrownBy(() -> StaticConfigLoader.parse("listeners:\n  - name: a\n    engine: ORACLE\n  - name: a\n    engine: POSTGRES\n"))
                .hasMessageContaining("duplicate listener name");
    }

    @Test
    void settingsFromEnvironment() {
        Map<String, String> env = Map.of("DBP_CONTROL_PLANE_URL", "http://cp:8080/", "DBP_PROXY_ID", "p2",
                "DBP_PROXY_ADMIN_PORT", "9999", "DBP_PROXY_IDLE_TIMEOUT_SECONDS", "600", "DBP_PROXY_BUFFER_BYTES", "65536");
        ProxySettings s = ProxySettings.fromEnv(env::get);
        assertThat(s.controlPlaneMode()).isTrue();
        assertThat(s.controlPlaneUrl()).isEqualTo("http://cp:8080");
        assertThat(s.proxyId()).isEqualTo("p2");
        assertThat(s.adminPort()).isEqualTo(9999);
        assertThat(s.idleTimeoutSeconds()).isEqualTo(600);
        assertThat(s.bufferBytes()).isEqualTo(65536);
        assertThat(s.configPollSeconds()).isEqualTo(5);
        assertThat(s.heartbeatSeconds()).isEqualTo(10);
        assertThat(ProxySettings.defaults().adminPort()).isEqualTo(7431);
        assertThat(ProxySettings.defaults().controlPlaneMode()).isFalse();
        assertThatThrownBy(() -> ProxySettings.fromEnv(Map.of("DBP_PROXY_ADMIN_PORT", "x")::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
