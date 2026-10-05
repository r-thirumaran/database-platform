package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.config.StaticConfigLoader;
import org.dbplatform.gateway.control.AuthException;
import org.dbplatform.gateway.control.SessionResolution;
import org.dbplatform.gateway.control.StaticResolver;
import org.dbplatform.gateway.pool.PoolMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticConfigTest {

    @Test
    void exampleYamlParses() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/gateway-example.yaml").readAllBytes(), StandardCharsets.UTF_8);
        StaticConfig cfg = StaticConfigLoader.parse(yaml, Map.<String, String>of()::get);
        assertThat(cfg.gatewayId()).isEqualTo("gw-local");
        assertThat(cfg.datasources()).extracting(StaticConfig.DatasourceConfig::name).containsExactly("sales", "inventory", "scratch");
        StaticConfig.DatasourceConfig sales = cfg.datasource("sales");
        assertThat(sales.passwordEnv()).isEqualTo("SALES_ORACLE_PASSWORD");
        assertThat(sales.maxConnections()).isEqualTo(40);
        assertThat(sales.jdbcProperties()).containsEntry("oracle.jdbc.ReadTimeout", "60000");
        assertThat(cfg.applications()).hasSize(2);
        StaticConfig.ApplicationConfig orders = cfg.applications().get(0);
        assertThat(orders.apiKey()).isEqualTo("dbp_orders_dev"); // placeholder default
        assertThat(orders.grant("sales").maxLogicalConnections()).isNull();
        assertThat(orders.grant("inventory").maxLogicalConnections()).isEqualTo(50);
        assertThat(orders.grant("inventory").readOnly()).isTrue();
        assertThat(cfg.applications().get(1).grant("scratch")).isNotNull();
    }

    @Test
    void placeholdersAndPasswordSources(@TempDir Path dir) throws Exception {
        Path pw = dir.resolve("pw.txt");
        Files.writeString(pw, "s3cret\n");
        String yaml = """
                gatewayId: ${GW_ID}
                datasources:
                  - name: a
                    jdbcUrl: jdbc:h2:mem:a
                    username: sa
                    passwordFile: %s
                  - name: b
                    jdbcUrl: jdbc:h2:mem:b
                    username: sa
                    passwordEnv: DBP_TEST_PW_B
                """.formatted(pw);
        StaticConfig cfg = StaticConfigLoader.parse(yaml, Map.of("GW_ID", "gw-9")::get);
        assertThat(cfg.gatewayId()).isEqualTo("gw-9");
        assertThat(StaticConfigLoader.resolvePassword(cfg.datasource("a"))).isEqualTo("s3cret");
        System.setProperty("DBP_TEST_PW_B", "from-env");
        try {
            assertThat(StaticConfigLoader.resolvePassword(cfg.datasource("b"))).isEqualTo("from-env");
        } finally {
            System.clearProperty("DBP_TEST_PW_B");
        }
        assertThatThrownBy(() -> StaticConfigLoader.parse("gatewayId: ${MISSING}\ndatasources: []", Map.<String, String>of()::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MISSING");
    }

    @Test
    void staticResolverAppliesGrantsAndPoolModes() throws Exception {
        String yaml = """
                gatewayId: gw
                datasources:
                  - { name: a, jdbcUrl: "jdbc:h2:mem:a", username: sa, password: "", poolMode: SESSION, maxConnections: 7 }
                  - { name: b, jdbcUrl: "jdbc:postgresql://x/y", username: u, password: p }
                applications:
                  - name: app
                    apiKey: k1
                    datasources: [a, { name: b, poolMode: SESSION, readOnly: true, maxLogicalConnections: 3 }]
                """;
        StaticResolver r = new StaticResolver(StaticConfigLoader.parse(yaml, Map.<String, String>of()::get));
        SessionResolution a = r.resolve("a", "k1", null, null);
        assertThat(a.poolMode()).isEqualTo(PoolMode.SESSION);
        assertThat(a.identity().application()).isEqualTo("app");
        assertThat(a.datasource().settings().maxConnections()).isEqualTo(7);
        assertThat(a.datasource().settings().engineHint()).isEqualTo(org.dbplatform.common.telemetry.Engine.H2);
        SessionResolution b = r.resolve("b", "k1", null, null);
        assertThat(b.poolMode()).isEqualTo(PoolMode.SESSION);
        assertThat(b.readOnly()).isTrue();
        assertThat(b.maxLogicalConnections()).isEqualTo(3);
        assertThat(b.datasource().settings().engineHint()).isEqualTo(org.dbplatform.common.telemetry.Engine.POSTGRES);
        assertThat(b.datasource().settings().maxConnections()).isEqualTo(StaticResolver.DEFAULT_MAX_CONNECTIONS);
        assertThatThrownBy(() -> r.resolve("a", "bad", null, null)).isInstanceOf(AuthException.class)
                .hasMessageContaining("invalid api key");
        assertThatThrownBy(() -> r.resolve("a", null, null, null)).isInstanceOf(AuthException.class)
                .hasMessageContaining("apiKey is required");
        assertThatThrownBy(() -> r.resolve("zzz", "k1", null, null)).isInstanceOf(AuthException.class)
                .hasMessageContaining("unknown datasource");

        StaticResolver open = new StaticResolver(StaticConfigLoader.parse("""
                datasources:
                  - { name: a, jdbcUrl: "jdbc:h2:mem:a", username: sa, password: "" }
                """, Map.<String, String>of()::get));
        assertThat(open.resolve("a", null, "hinted-app", "bob").identity().application()).isEqualTo("hinted-app");
        assertThat(open.resolve("a", null, null, "bob").identity().application()).isEqualTo("bob");
        assertThat(open.resolve("a", null, null, null).identity().application()).isEqualTo("unknown");
    }
}
