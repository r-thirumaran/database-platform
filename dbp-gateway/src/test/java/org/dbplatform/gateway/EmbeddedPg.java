package org.dbplatform.gateway;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Embedded PostgreSQL for the tests. Uses zonky's {@link EmbeddedPostgres} when the JVM runs as an ordinary user.
 * PostgreSQL refuses to run as root, so under root the server is started through {@code runuser -u <unprivileged>}
 * with the system binaries ({@code /usr/lib/postgresql/<ver>/bin}, {@code DBP_TEST_PG_BIN}) or, failing that, the
 * binaries bundled with zonky, unpacked by us.
 */
public final class EmbeddedPg implements AutoCloseable {

    private final EmbeddedPostgres zonky;
    private final RootPg root;
    private final int port;

    private EmbeddedPg(EmbeddedPostgres zonky, RootPg root, int port) {
        this.zonky = zonky;
        this.root = root;
        this.port = port;
    }

    public static EmbeddedPg start() throws IOException {
        if (isRoot()) {
            RootPg r = RootPg.start();
            return new EmbeddedPg(null, r, r.port);
        }
        EmbeddedPostgres z = EmbeddedPostgres.builder().start();
        return new EmbeddedPg(z, null, z.getPort());
    }

    public int port() {
        return port;
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    public Connection superuser(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres");
    }

    @Override
    public void close() throws IOException {
        if (zonky != null) {
            zonky.close();
        }
        if (root != null) {
            root.close();
        }
    }

    static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    // ------------------------------------------------------------------ root mode

    private static final class RootPg implements AutoCloseable {
        private final Path bin;
        private final Path data;
        private final String user;
        private final int port;
        private final Path unpacked;

        private RootPg(Path bin, Path data, String user, int port, Path unpacked) {
            this.bin = bin;
            this.data = data;
            this.user = user;
            this.port = port;
            this.unpacked = unpacked;
        }

        static RootPg start() throws IOException {
            String user = unprivilegedUser();
            Path unpacked = null;
            Path bin = systemBinaries();
            if (bin == null) {
                unpacked = unpackZonkyBinaries();
                bin = unpacked.resolve("bin");
            }
            Path data = Files.createTempDirectory(Path.of("/tmp"), "dbp-pg-");
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
            chown(data, user);
            int port = freePort();
            RootPg pg = new RootPg(bin, data, user, port, unpacked);
            pg.run(60, bin.resolve("initdb").toString(), "-A", "trust", "-U", "postgres", "-D", data.toString(),
                    "-E", "UTF8", "--no-sync", "--locale=C");
            pg.run(60, bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-w", "-t", "60",
                    "-l", data.resolve("pg.log").toString(),
                    "-o", "-p " + port + " -F -c listen_addresses=127.0.0.1 -c unix_socket_directories=" + data
                            + " -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=100",
                    "start");
            return pg;
        }

        private void run(int timeoutSeconds, String... cmd) throws IOException {
            List<String> full = new ArrayList<>();
            full.add("runuser");
            full.add("-u");
            full.add(user);
            full.add("--");
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

        @Override
        public void close() throws IOException {
            try {
                run(30, bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-m", "immediate", "stop");
            } finally {
                deleteRecursively(data);
                if (unpacked != null) {
                    deleteRecursively(unpacked);
                }
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

        private static Path systemBinaries() throws IOException {
            String env = System.getenv("DBP_TEST_PG_BIN");
            if (env != null && Files.isExecutable(Path.of(env, "initdb"))) {
                return Path.of(env);
            }
            Path root = Path.of("/usr/lib/postgresql");
            if (!Files.isDirectory(root)) {
                return null;
            }
            try (Stream<Path> versions = Files.list(root)) {
                return versions.filter(v -> Files.isExecutable(v.resolve("bin/initdb")) && Files.isExecutable(v.resolve("bin/postgres")))
                        .max(Comparator.comparingInt(v -> parseVersion(v.getFileName().toString())))
                        .map(v -> v.resolve("bin")).orElse(null);
            }
        }

        private static int parseVersion(String s) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Unpacks zonky's {@code postgres-linux-x86_64.txz} (commons-compress + xz are on the test classpath). */
        private static Path unpackZonkyBinaries() throws IOException {
            String arch = System.getProperty("os.arch").contains("aarch64") ? "arm_64" : "x86_64";
            InputStream res = EmbeddedPg.class.getResourceAsStream("/postgres-linux-" + arch + ".txz");
            if (res == null) {
                throw new IOException("no PostgreSQL binaries: neither system binaries nor zonky bundle found");
            }
            Path dir = Files.createTempDirectory(Path.of("/tmp"), "dbp-pg-bin-");
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new XZCompressorInputStream(new BufferedInputStream(res)))) {
                TarArchiveEntry e;
                while ((e = tar.getNextEntry()) != null) {
                    Path target = dir.resolve(e.getName()).normalize();
                    if (!target.startsWith(dir)) {
                        continue;
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(target);
                    } else if (e.isSymbolicLink()) {
                        Files.createDirectories(target.getParent());
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, Path.of(e.getLinkName()));
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(tar, target);
                        if ((e.getMode() & 0111) != 0) {
                            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
                        }
                    }
                }
            }
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            try (Stream<Path> all = Files.walk(dir)) {
                all.filter(Files::isDirectory).forEach(d -> {
                    try {
                        Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwxr-xr-x"));
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
            return dir;
        }

        private static void chown(Path p, String user) throws IOException {
            UserPrincipal principal = p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
            Files.setOwner(p, principal);
        }

        private static int freePort() throws IOException {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            }
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
    }
}
