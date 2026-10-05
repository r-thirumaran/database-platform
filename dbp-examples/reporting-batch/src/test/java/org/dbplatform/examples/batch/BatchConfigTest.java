package org.dbplatform.examples.batch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchConfigTest {

    @Test
    void defaultsApplyWhenOnlyUrlIsGiven() {
        BatchConfig c = BatchConfig.fromEnv(Map.of("DBP_JDBC_URL", "jdbc:oracle:thin:@//oracle:1521/FREEPDB1"));
        assertThat(c.connections()).isEqualTo(20);
        assertThat(c.duration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.engine()).isEqualTo(Engine.ORACLE);
        assertThat(c.updateEvery()).isEqualTo(10);
        assertThat(c.programName()).isEqualTo("reporting-batch");
        assertThat(c.driverClass()).isNull();
    }

    @Test
    void urlIsRequired() {
        assertThatThrownBy(() -> BatchConfig.fromEnv(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DBP_JDBC_URL");
    }

    @Test
    void engineIsDetectedFromUrlOrOverridden() {
        assertThat(Engine.detect("jdbc:postgresql://postgres:5432/sales", null)).isEqualTo(Engine.POSTGRES);
        assertThat(Engine.detect("jdbc:oracle:thin:@//proxy:1521/sales.reporting-batch", null)).isEqualTo(Engine.ORACLE);
        assertThat(Engine.detect("jdbc:dbp://gateway:7420/sales", null)).isEqualTo(Engine.ORACLE);
        assertThat(Engine.detect("jdbc:dbp://gateway:7420/sales", "postgres")).isEqualTo(Engine.POSTGRES);
        assertThatThrownBy(() -> Engine.detect("jdbc:dbp://gateway:7420/sales", "db2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionPropertiesCarryTheProgramNamePerDriver() {
        Map<String, String> env = new HashMap<>();
        env.put("DBP_JDBC_USER", "SALES_APP");
        env.put("DBP_JDBC_PASSWORD", "secret");

        env.put("DBP_JDBC_URL", "jdbc:oracle:thin:@//oracle:1521/FREEPDB1");
        Properties oracle = BatchConfig.fromEnv(env).connectionProperties();
        assertThat(oracle.getProperty("v$session.program")).isEqualTo("reporting-batch");
        assertThat(oracle.getProperty("user")).isEqualTo("SALES_APP");
        assertThat(oracle.getProperty("password")).isEqualTo("secret");

        env.put("DBP_JDBC_URL", "jdbc:postgresql://postgres:5432/sales");
        assertThat(BatchConfig.fromEnv(env).connectionProperties().getProperty("ApplicationName")).isEqualTo("reporting-batch");

        env.put("DBP_JDBC_URL", "jdbc:dbp://gateway:7420/sales");
        env.put("DBP_BATCH_PROGRAM", "nightly-report");
        Properties dbp = BatchConfig.fromEnv(env).connectionProperties();
        assertThat(dbp.getProperty("clientInfo.ApplicationName")).isEqualTo("nightly-report");
        assertThat(dbp).doesNotContainKey("v$session.program");
    }

    @Test
    void invalidNumbersAreRejected() {
        Map<String, String> env = Map.of("DBP_JDBC_URL", "jdbc:oracle:thin:@//o:1521/X", "DBP_BATCH_CONNECTIONS", "many");
        assertThatThrownBy(() -> BatchConfig.fromEnv(env)).hasMessageContaining("DBP_BATCH_CONNECTIONS");
        Map<String, String> zero = Map.of("DBP_JDBC_URL", "jdbc:oracle:thin:@//o:1521/X", "DBP_BATCH_CONNECTIONS", "0");
        assertThatThrownBy(() -> BatchConfig.fromEnv(zero)).hasMessageContaining(">= 1");
    }

    @Test
    void maskedUrlHidesQueryString() {
        BatchConfig c = BatchConfig.fromEnv(Map.of("DBP_JDBC_URL", "jdbc:dbp://gateway:7420/sales?apiKey=dbp_x_y"));
        assertThat(c.maskedUrl()).isEqualTo("jdbc:dbp://gateway:7420/sales?...");
    }

    @Test
    void workloadParametersMatchStatements() {
        assertThat(Workload.SELECT_PARAMS).hasSameSizeAs(Workload.SELECTS);
        for (int i = 0; i < Workload.SELECTS.size(); i++) {
            long placeholders = Workload.SELECTS.get(i).chars().filter(ch -> ch == '?').count();
            assertThat(placeholders).as("statement %d", i + 1)
                    .isEqualTo(Workload.SELECT_PARAMS.get(i) == Workload.Params.NONE ? 0 : 1);
        }
    }

    @Test
    void statsPercentilesAndCounters() {
        Stats s = new Stats();
        for (int i = 1; i <= 100; i++) {
            s.statement(i * 1000L, 1);
        }
        s.updateBatch(10);
        s.error();
        assertThat(s.statements()).isEqualTo(110);
        assertThat(s.updates()).isEqualTo(10);
        assertThat(s.errors()).isEqualTo(1);
        assertThat(s.rows()).isEqualTo(100);
        assertThat(s.percentileMillis(50)).isEqualTo(50.0);
        assertThat(s.percentileMillis(99)).isEqualTo(99.0);
        assertThat(new Stats().percentileMillis(95)).isEqualTo(0.0);
    }
}
