package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.service.TeamService;
import org.dbplatform.controlplane.service.seed.DemoSeedService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the context on the {@code postgres} profile against an embedded PostgreSQL (zonky) to prove the
 * Flyway migrations and the JPA mappings work on the real engine, not only on H2 in PostgreSQL mode.
 */
@SpringBootTest(properties = {"dbp.collector.enabled=false", "dbp.governance.interval-seconds=36000", "dbp.telemetry.cleanup-interval-seconds=36000", "dbp.demo.seed-enabled=true"})
@ActiveProfiles("postgres")
class PostgresMigrationTest {
    private static EmbeddedPostgres pg;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        pg = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws IOException { if (pg != null) pg.close(); }

    @Autowired DataSource dataSource;
    @Autowired TeamService teams;
    @Autowired DemoSeedService seed;

    @Test
    void migrationsAppliedAndSeedWorksOnPostgres() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("select count(*) from flyway_schema_history where success")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("select version from config_version where id = 1")) {
                rs.next();
                assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("select data_type from information_schema.columns where table_name = 'team' and column_name = 'created_at'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("timestamp with time zone");
            }
        }
        Team t = new Team();
        t.setName("pg-team");
        t.setContacts(List.of("pg@example.org"));
        Team saved = teams.create(t);
        assertThat(teams.get(saved.getId()).getContacts()).containsExactly("pg@example.org");
        DemoSeedService.SeedResult r = seed.seed();
        assertThat(r.teams()).isEqualTo(5);
        assertThat(seed.seed().relationships()).isZero(); // idempotent on PostgreSQL too
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select count(*) from db_table where kind = '" + Enums.TableKind.VIEW + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        Map<String, Object> ignored = Map.of();
        assertThat(ignored).isEmpty();
    }
}
