package org.dbplatform.examples.batch;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Configuration from environment variables, so the very same jar runs in every mode:
 * <pre>
 *   DBP_JDBC_URL        jdbc:oracle:thin:@//oracle:1521/FREEPDB1
 *                       jdbc:oracle:thin:@//proxy:1521/sales.reporting-batch
 *                       jdbc:dbp://gateway:7420/sales
 *   DBP_JDBC_USER       SALES_APP | reporting-batch (gateway, informational)
 *   DBP_JDBC_PASSWORD   password | API key (gateway)
 *   DBP_JDBC_DRIVER     optional driver class (DriverManager finds registered drivers by itself)
 *   DBP_DEMO_ENGINE     ORACLE | POSTGRES, needed for jdbc:dbp URLs (default ORACLE)
 *   DBP_BATCH_CONNECTIONS       logical connections / virtual threads (default 20)
 *   DBP_BATCH_DURATION_SECONDS  run time (default 60)
 *   DBP_BATCH_THINK_MS          pause between iterations per worker (default 0)
 *   DBP_BATCH_UPDATE_EVERY      every n-th iteration runs an UPDATE batch (default 10)
 *   DBP_BATCH_UPDATE_SIZE       rows per UPDATE batch (default 10)
 *   DBP_BATCH_REPORT_SECONDS    progress line interval (default 10)
 *   DBP_BATCH_PROGRAM           program / application name for attribution (default reporting-batch)
 * </pre>
 */
public record BatchConfig(String jdbcUrl,
                          String user,
                          String password,
                          String driverClass,
                          Engine engine,
                          int connections,
                          Duration duration,
                          long thinkMillis,
                          int updateEvery,
                          int updateBatchSize,
                          int reportIntervalSeconds,
                          String programName) {

    public static final String DEFAULT_PROGRAM = "reporting-batch";

    public BatchConfig {
        Objects.requireNonNull(jdbcUrl, "DBP_JDBC_URL is required");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("DBP_JDBC_URL is required");
        }
        if (connections < 1) {
            throw new IllegalArgumentException("DBP_BATCH_CONNECTIONS must be >= 1");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("DBP_BATCH_DURATION_SECONDS must be > 0");
        }
        if (updateEvery < 0 || updateBatchSize < 0) {
            throw new IllegalArgumentException("update settings must be >= 0");
        }
    }

    public static BatchConfig fromEnv(Map<String, String> env) {
        String url = env.get("DBP_JDBC_URL");
        return new BatchConfig(
                url == null ? "" : url,
                env.getOrDefault("DBP_JDBC_USER", ""),
                env.getOrDefault("DBP_JDBC_PASSWORD", ""),
                blankToNull(env.get("DBP_JDBC_DRIVER")),
                Engine.detect(url, env.get("DBP_DEMO_ENGINE")),
                intEnv(env, "DBP_BATCH_CONNECTIONS", 20),
                Duration.ofSeconds(intEnv(env, "DBP_BATCH_DURATION_SECONDS", 60)),
                intEnv(env, "DBP_BATCH_THINK_MS", 0),
                intEnv(env, "DBP_BATCH_UPDATE_EVERY", 10),
                intEnv(env, "DBP_BATCH_UPDATE_SIZE", 10),
                intEnv(env, "DBP_BATCH_REPORT_SECONDS", 10),
                env.getOrDefault("DBP_BATCH_PROGRAM", DEFAULT_PROGRAM));
    }

    /** Driver properties that make the batch identifiable on the database side, per driver family. */
    public Properties connectionProperties() {
        Properties p = new Properties();
        if (!user.isEmpty()) {
            p.setProperty("user", user);
        }
        if (!password.isEmpty()) {
            p.setProperty("password", password);
        }
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:oracle:")) {
            p.setProperty("v$session.program", programName);    // V$SESSION.PROGRAM
            p.setProperty("oracle.jdbc.ReadTimeout", "60000");
        } else if (url.startsWith("jdbc:postgresql:")) {
            p.setProperty("ApplicationName", programName);      // pg_stat_activity.application_name
        } else if (url.startsWith("jdbc:dbp:")) {
            p.setProperty("clientInfo.ApplicationName", programName);
            p.setProperty("application", programName);
        }
        return p;
    }

    public String maskedUrl() {
        int q = jdbcUrl.indexOf('?');
        return q < 0 ? jdbcUrl : jdbcUrl.substring(0, q) + "?...";
    }

    private static int intEnv(Map<String, String> env, String name, int def) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + v + "'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
