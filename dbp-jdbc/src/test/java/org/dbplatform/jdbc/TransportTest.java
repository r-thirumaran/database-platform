package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.Ping;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportTest extends GatewayTest {

    private static int deadPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    void failsOverToTheSecondHost() throws Exception {
        int dead = deadPort();
        String url = "jdbc:dbp://127.0.0.1:" + dead + ",127.0.0.1:" + gateway.port() + "/sales?apiKey=k";
        try (Connection c = DriverManager.getConnection(url)) {
            assertThat(c.isValid(1)).isTrue();
            assertThat(gateway.sessionCount()).isEqualTo(1);
            assertThat(c.toString()).contains(":" + gateway.port());
        }
    }

    @Test
    void allHostsDownIs08001() throws Exception {
        int dead1 = deadPort();
        int dead2 = deadPort();
        String url = "jdbc:dbp://127.0.0.1:" + dead1 + ",127.0.0.1:" + dead2 + "/sales?apiKey=k&connectTimeoutMs=500";
        assertThatThrownBy(() -> DriverManager.getConnection(url))
                .isInstanceOf(SQLNonTransientConnectionException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"))
                .hasMessageContaining(Integer.toString(dead2));
    }

    @Test
    void oneConnectionIsSafeToShareBetweenThreads() throws Exception {
        try (Connection c = connect("apiKey=k&fetchSize=2")) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                results.add(pool.submit(() -> {
                    int total = 0;
                    for (int i = 0; i < 25; i++) {
                        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select 5 rows")) {
                            int n = 0;
                            while (rs.next()) {
                                n += rs.getInt(1);
                            }
                            total += n;
                        }
                    }
                    return total;
                }));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(25 * 15);
            }
            pool.shutdown();
            assertThat(gateway.received(Execute.class)).hasSize(100);
            assertThat(c.isValid(1)).isTrue();
        }
    }

    @Test
    void isValidHonoursItsTimeout() throws Exception {
        try (Connection c = connect()) {
            // the gateway never answers the PING: only the driver's own timeout can make isValid return, so no
            // sleep-versus-bound race on a slow runner
            gateway.setHandler((req, session) -> {
                if (req instanceof Ping) {
                    return;
                }
                gateway.echo().handle(req, session);
            });
            long start = System.nanoTime();
            boolean valid = c.isValid(1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(valid).isFalse();
            assertThat(elapsedMs).as("returned by the 1 s socket timeout").isBetween(800L, 30_000L);
            assertThat(c.isClosed()).isTrue();
        }
    }

    @Test
    void socketTimeoutPropertyKillsSlowCalls() throws Exception {
        try (Connection c = connect("apiKey=k&socketTimeoutMs=300"); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("sleep 10")).isZero();
            assertThatThrownBy(() -> s.executeUpdate("sleep 2000"))
                    .isInstanceOf(SQLNonTransientConnectionException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08006"));
            assertThat(c.isClosed()).isTrue();
        }
    }
}
