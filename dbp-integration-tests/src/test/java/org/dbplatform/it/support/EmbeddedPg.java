package org.dbplatform.it.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Embedded PostgreSQL for the integration tests, started from the system binaries ({@code /usr/lib/postgresql/<ver>/bin},
 * override with {@code DBP_TEST_PG_BIN}). PostgreSQL refuses to run as root, so under root the server runs through
 * {@code runuser -u postgres} (or {@code nobody}) exactly like {@code dbp-gateway}'s test helper; as an ordinary user the
 * binaries are invoked directly. Password authentication is real ({@code scram-sha-256}), so wrong credentials fail.
 */
public final class EmbeddedPg implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedPg.class);
    public static final String SUPERUSER = "postgres";
    public static final String SUPERUSER_PASSWORD = "postgres";

    private final Path bin;
    private final Path base;
    private final Path data;
    private final String runAsUser;
    private final int port;
    private final Path logDir;
    private boolean statStatements;

    private EmbeddedPg(Path bin, Path base, String runAsUser, int port, Path logDir) {
        this.bin = bin;
        this.base = base;
        this.data = base.resolve("data");
        this.runAsUser = runAsUser;
        this.port = port;
        this.logDir = logDir;
    }

    public static EmbeddedPg start(Path workDir, Path logDir) throws IOException {
        Path bin = binaries();
        boolean root = "root".equals(System.getProperty("user.name"));
        String user = root ? unprivilegedUser() : null;
        Files.createDirectories(workDir);
        Path base = Files.createTempDirectory(workDir, "pg-");
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        Path pw = base.resolve("pw");
        Files.writeString(pw, SUPERUSER_PASSWORD + "\n", StandardCharsets.UTF_8);
        if (user != null) {
            chown(base, user);
            chown(pw, user);
        }
        EmbeddedPg pg = new EmbeddedPg(bin, base, user, Ports.free(), logDir);
        LOG.info("PostgreSQL binaries {} ({}), data {}, port {}", bin, pg.version(), pg.data, pg.port);
        pg.run(120, bin.resolve("initdb").toString(), "-A", "scram-sha-256", "-U", SUPERUSER, "--pwfile=" + pw,
                "-D", pg.data.toString(), "-E", "UTF8", "--no-sync", "--locale=C");
        try {
            pg.startServer(true);
            pg.statStatements = true;
        } catch (IOException e) {
            LOG.warn("start with pg_stat_statements failed ({}), retrying without", e.getMessage().lines().findFirst().orElse(""));
            pg.startServer(false);
        }
        return pg;
    }

    private void startServer(boolean withStatStatements) throws IOException {
        String opts = "-p " + port + " -F -c listen_addresses=127.0.0.1 -c unix_socket_directories=" + base
                + " -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=100"
                + " -c log_min_messages=warning"
                + (withStatStatements ? " -c shared_preload_libraries=pg_stat_statements" : "");
        run(90, bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-w", "-t", "60", "-l", data.resolve("pg.log").toString(),
                "-o", opts, "start");
    }

    public int port() {
        return port;
    }

    public Path binDir() {
        return bin;
    }

    public boolean statStatementsLoaded() {
        return statStatements;
    }

    public String version() {
        try {
            return new ProcessBuilder(bin.resolve("postgres").toString(), "--version").redirectErrorStream(true).start()
                    .inputReader(StandardCharsets.UTF_8).readLine();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    public Connection superuser(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), SUPERUSER, SUPERUSER_PASSWORD);
    }

    public Connection connect(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), user, password);
    }

    /**
     * Runs a psql script ({@code psql -v ON_ERROR_STOP=1 -f script}) as the superuser against {@code database}. psql's
     * meta-commands ({@code \connect}, {@code \set}) work unchanged. Returns the combined output; throws with the output
     * when psql exits non-zero.
     */
    public String psql(String database, Path script) throws IOException {
        Path psql = bin.resolve("psql");
        if (!Files.isExecutable(psql)) {
            throw new IOException("psql not found at " + psql);
        }
        List<String> cmd = List.of(psql.toString(), "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", String.valueOf(port),
                "-U", SUPERUSER, "-d", database, "-f", script.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", SUPERUSER_PASSWORD);
        pb.environment().put("PGCONNECT_TIMEOUT", "10");
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("psql timed out on " + script + "\n" + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("psql failed (" + p.exitValue() + ") on " + script.getFileName() + ":\n" + out);
        }
        return out;
    }

    public boolean hasPsql() {
        return Files.isExecutable(bin.resolve("psql"));
    }

    @Override
    public void close() {
        try {
            run(60, bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-m", "fast", "-w", "-t", "30", "stop");
        } catch (IOException e) {
            LOG.warn("pg_ctl stop failed: {}", e.getMessage());
            try {
                run(30, bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-m", "immediate", "stop");
            } catch (IOException ignored) {
                // best effort
            }
        } finally {
            try {
                Files.createDirectories(logDir);
                Path log = data.resolve("pg.log");
                if (Files.exists(log)) {
                    Files.copy(log, logDir.resolve("postgres.log"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.warn("cannot keep pg.log: {}", e.getMessage());
            }
            deleteRecursively(base);
        }
    }

    // ------------------------------------------------------------------ internals

    private void run(int timeoutSeconds, String... cmd) throws IOException {
        List<String> full = new ArrayList<>();
        if (runAsUser != null) {
            full.addAll(List.of("runuser", "-u", runAsUser, "--"));
        }
        full.addAll(List.of(cmd));
        ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("timed out: " + full + "\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("command failed (" + p.exitValue() + "): " + full + "\n" + output);
        }
    }

    private static String unprivilegedUser() throws IOException {
        for (String u : new String[] {"postgres", "nobody"}) {
            try {
                Process p = new ProcessBuilder("id", "-u", u).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return u;
                }
            } catch (IOException | InterruptedException ignored) {
                // try the next one
            }
        }
        throw new IOException("running as root and no unprivileged user (postgres/nobody) found to run PostgreSQL");
    }

    private static Path binaries() throws IOException {
        for (String env : new String[] {System.getenv("DBP_TEST_PG_BIN"), System.getProperty("dbp.it.pgBin")}) {
            if (env != null && !env.isBlank() && Files.isExecutable(Path.of(env, "initdb"))) {
                return Path.of(env);
            }
        }
        Path root = Path.of("/usr/lib/postgresql");
        if (Files.isDirectory(root)) {
            try (Stream<Path> versions = Files.list(root)) {
                var best = versions.filter(v -> Files.isExecutable(v.resolve("bin/initdb")) && Files.isExecutable(v.resolve("bin/postgres")))
                        .max(Comparator.comparingInt(v -> parseVersion(v.getFileName().toString())));
                if (best.isPresent()) {
                    return best.get().resolve("bin");
                }
            }
        }
        for (String candidate : new String[] {"/usr/local/pgsql/bin", "/usr/pgsql-16/bin", "/usr/pgsql-17/bin", "/opt/homebrew/opt/postgresql@16/bin"}) {
            if (Files.isExecutable(Path.of(candidate, "initdb"))) {
                return Path.of(candidate);
            }
        }
        // last resort: whatever initdb is on the PATH
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v initdb").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank()) {
                return Path.of(out).toRealPath().getParent();
            }
        } catch (IOException | InterruptedException ignored) {
            // fall through
        }
        throw new IOException("no PostgreSQL server binaries found: install postgresql (server) or set DBP_TEST_PG_BIN=<dir with initdb/pg_ctl/postgres>");
    }

    private static int parseVersion(String s) {
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void chown(Path p, String user) throws IOException {
        UserPrincipal principal = p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
        Files.setOwner(p, principal);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Environment for child processes that need to reach this server with the superuser. */
    public Map<String, String> clientEnv() {
        return Map.of("PGHOST", "127.0.0.1", "PGPORT", String.valueOf(port), "PGUSER", SUPERUSER, "PGPASSWORD", SUPERUSER_PASSWORD);
    }
}
