package org.dbplatform.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.tools.Server;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The system under test as real processes: embedded PostgreSQL (demo schema loaded from {@code demo/sql/postgres}),
 * the control plane jar, the gateway shaded jar in control-plane mode, optionally the proxy shaded jar and a second
 * gateway in static mode in front of an H2 (MODE=Oracle) TCP server. Started once per test run, torn down when the
 * run ends (also on failure). Every {@code ensure*} method is idempotent so a single scenario class can run alone.
 */
public final class Stack implements ExtensionContext.Store.CloseableResource {
    private static final Logger LOG = LoggerFactory.getLogger(Stack.class);

    public static final String SERVICE_TOKEN = "it-token";
    public static final String SALES_APP_PASSWORD = "SalesApp#Demo2026";
    public static final String COLLECTOR_PASSWORD = "Collector#Demo2026";
    public static final String MASTER_KEY = "it-master-key";
    public static final String GATEWAY_ID = "it-gw";
    public static final String PROXY_ID = "it-proxy";
    public static final String H2_GATEWAY_ID = "it-gw-h2";
    public static final String H2_API_KEY = "dbp_it_h2_static";
    public static final String H2_DATASOURCE = "sales-h2";

    public static final String ORDERS = "orders-service";
    public static final String BATCH = "reporting-batch";
    public static final String LEGACY = "legacy-reporting";
    public static final String DS_SALES = "sales";
    public static final String DB_PG = "sales-postgres";
    public static final String DB_ALT = "sales-alt";
    public static final String DB_ORACLE = "sales-oracle";
    public static final String CRED_APP = "sales-postgres-app";
    public static final String CRED_COLLECTOR = "sales-postgres-collector";
    public static final int SALES_MAX_CONNECTIONS = 4;

    public record GatewayInfo(int port, int adminPort, String adminUrl) {
    }

    public record ProxyInfo(String mode, int pgPort, int adminPort, String adminUrl) {
    }

    public record H2Info(int gatewayPort, int adminPort, int h2TcpPort, String dbpUrl, String adminUrl) {
    }

    public record Bootstrap(JsonNode importResult, ObjectNode document) {
    }

    private static volatile Stack current;

    private final Path work = workDir();
    private final Path logs = work.resolve("logs");
    private final Instant startedAt = Instant.now();

    private EmbeddedPg pg;
    private ManagedProcess controlPlane;
    private ControlPlaneApi cp;
    private int cpPort;
    private ManagedProcess gateway;
    private int gatewayPort = -1;
    private int gatewayAdminPort;
    private ManagedProcess proxy;
    private int proxyAdminPort;
    private int proxyPgPort;
    private String proxyMode;
    private Server h2Server;
    private Connection h2Keeper;
    private ManagedProcess h2Gateway;
    private int h2GatewayPort;
    private int h2GatewayAdminPort;
    private int h2TcpPort;
    private boolean bootstrapped;
    private ObjectNode importDocument;
    private JsonNode importResult;
    private boolean closed;

    /** {@code kind:name} → id (kind ∈ team, app, db, ds, cred, apikey). */
    public final Map<String, String> ids = new ConcurrentHashMap<>();
    public final Map<String, String> apiKeys = new ConcurrentHashMap<>();
    /** Infrastructure observations (script warnings, port substitutions …) for the report. */
    public final List<String> findings = Collections.synchronizedList(new ArrayList<>());

    private Stack() {
    }

    public static Stack current() {
        Stack s = current;
        if (s == null) {
            throw new IllegalStateException("Stack not started: annotate the test class with @ExtendWith(ItExtension.class)");
        }
        return s;
    }

    public static Path workDir() {
        return Path.of(System.getProperty("dbp.it.workDir", "target/it")).toAbsolutePath().normalize();
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("dbp.it.repoRoot", "..")).toAbsolutePath().normalize();
    }

    static Stack create() {
        Stack s = new Stack();
        try {
            s.startInfrastructure();
        } catch (Exception e) {
            s.close();
            throw new IllegalStateException("integration test infrastructure failed to start: " + e.getMessage(), e);
        }
        current = s;
        Runtime.getRuntime().addShutdownHook(new Thread(s::close, "dbp-it-shutdown"));
        return s;
    }

    // ------------------------------------------------------------------ infrastructure

    private void startInfrastructure() throws Exception {
        Files.createDirectories(logs);
        deleteRecursively(work.resolve("cp-data"));
        pg = EmbeddedPg.start(work, logs);
        if (!pg.statStatementsLoaded()) {
            findings.add("pg_stat_statements could not be preloaded; the runtime collector falls back to pg_stat_activity only");
        }
        loadDemoSchema();
        createAlternateDatabase();
        startControlPlane();
    }

    private void loadDemoSchema() throws IOException {
        Path dir = repoRoot().resolve("demo/sql/postgres");
        for (String f : List.of("00_databases.sql", "10_schema.sql", "20_plpgsql.sql", "30_data.sql", "40_grants_app.sql")) {
            Path script = dir.resolve(f);
            long t0 = System.nanoTime();
            String out;
            if (pg.hasPsql()) {
                out = pg.psql("postgres", script);
            } else {
                findings.add("psql not available: demo scripts loaded through JDBC with psql meta-commands stripped");
                out = loadViaJdbc(script);
            }
            out.lines().filter(l -> l.contains("ERROR") || l.contains("WARNING")).forEach(l -> findings.add(f + ": " + l.trim()));
            LOG.info("{} loaded in {} ms", f, (System.nanoTime() - t0) / 1_000_000);
        }
        try (Connection c = pg.superuser("sales")) {
            long customers = Sql.queryLong(c, "SELECT count(*) FROM sales.customer");
            long orders = Sql.queryLong(c, "SELECT count(*) FROM sales.orders");
            LOG.info("demo data: {} customers, {} orders", customers, orders);
            findings.add("demo schema loaded unchanged with psql: " + customers + " customers, " + orders + " orders");
        } catch (SQLException e) {
            throw new IOException("demo schema check failed: " + e.getMessage(), e);
        }
    }

    /** Fallback when psql is missing: everything before {@code \connect sales} runs on database postgres, the rest on sales. */
    private String loadViaJdbc(Path script) throws IOException {
        String text = Files.readString(script, StandardCharsets.UTF_8);
        String[] parts = text.split("(?m)^\\\\connect\\s+sales\\s*$", 2);
        try {
            runSql("postgres", stripMeta(parts[0]));
            if (parts.length > 1) {
                runSql("sales", stripMeta(parts[1]));
            }
        } catch (SQLException e) {
            throw new IOException(script.getFileName() + " failed via JDBC: " + e.getMessage(), e);
        }
        return "";
    }

    private static String stripMeta(String sql) {
        return sql.lines().filter(l -> !l.startsWith("\\")).reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append).toString();
    }

    private void runSql(String db, String sql) throws SQLException {
        if (sql.isBlank()) {
            return;
        }
        try (Connection c = pg.superuser(db); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void createAlternateDatabase() throws SQLException {
        try (Connection c = pg.superuser("postgres")) {
            Sql.exec(c, "CREATE DATABASE sales_alt OWNER sales ENCODING 'UTF8' TEMPLATE template0",
                    "GRANT CONNECT ON DATABASE sales_alt TO sales_app", "REVOKE CONNECT ON DATABASE sales_alt FROM PUBLIC");
        }
        try (Connection c = pg.superuser("sales_alt")) {
            Sql.exec(c, "CREATE SCHEMA sales AUTHORIZATION sales",
                    "CREATE TABLE sales.customer (id bigint PRIMARY KEY, email varchar(200) NOT NULL UNIQUE, first_name varchar(100),"
                            + " last_name varchar(100), country_code char(2), status varchar(20) NOT NULL DEFAULT 'ACTIVE')",
                    "INSERT INTO sales.customer (id, email, first_name, last_name, country_code) VALUES"
                            + " (1, 'alt1@example.org', 'Alt', 'One', 'DE'), (2, 'alt2@example.org', 'Alt', 'Two', 'FR'), (3, 'alt3@example.org', 'Alt', 'Three', 'NL')",
                    "ALTER TABLE sales.customer OWNER TO sales",
                    "GRANT USAGE ON SCHEMA sales TO sales_app",
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA sales TO sales_app",
                    "ALTER ROLE sales_app IN DATABASE sales_alt SET search_path = sales, public");
        }
    }

    private void startControlPlane() {
        Path jar = resolveJar("dbp.it.controlPlaneJar", "control plane", List.of(repoRoot().resolve("dbp-control-plane/target/dbp-control-plane.jar")), "dbp-control-plane*.jar")
                .orElseThrow(() -> new IllegalStateException("control plane jar not found: build dbp-control-plane or pass -Ddbp.it.controlPlaneJar=..."));
        int preferred = Integer.getInteger("dbp.it.controlPlanePort", 18080);
        cpPort = Ports.preferred(preferred);
        if (cpPort != preferred) {
            findings.add("control plane port " + preferred + " was busy, used " + cpPort);
        }
        Path data = work.resolve("cp-data");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DBP_PORT", String.valueOf(cpPort));
        env.put("DBP_SERVICE_TOKEN", SERVICE_TOKEN);
        env.put("DBP_DEMO_SEED_ENABLED", "false");
        env.put("DBP_MASTER_KEY", MASTER_KEY);
        env.put("DBP_DB_URL", "jdbc:h2:file:" + data.resolve("dbp") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        env.put("DBP_COLLECTOR_TICK_SECONDS", "2");
        env.put("DBP_COMPONENT_HEALTHY_SECONDS", "30");
        env.put("DBP_GOVERNANCE_INTERVAL_SECONDS", "60");
        env.put("DBP_COLLECTOR_PASSWORD", COLLECTOR_PASSWORD);
        env.put("SALES_APP_PASSWORD", SALES_APP_PASSWORD);
        env.put("SPRING_PROFILES_ACTIVE", "dev");
        controlPlane = ManagedProcess.start("control-plane", logs, work, env, List.of(javaBin(), "-Xmx768m", "-XX:TieredStopAtLevel=1",
                "-Dspring.main.banner-mode=off", "-jar", jar.toString(), "--server.port=" + cpPort));
        cp = new ControlPlaneApi("http://127.0.0.1:" + cpPort, SERVICE_TOKEN);
        awaitHealthy(controlPlane, cp.baseUrl() + "/actuator/health", Duration.ofSeconds(240));
        LOG.info("control plane up on port {} after {} s", cpPort, Duration.between(startedAt, Instant.now()).toSeconds());
    }

    // ------------------------------------------------------------------ bootstrap (scenario 1)

    /** Imports the adapted bootstrap document and issues api keys; idempotent. */
    public synchronized Bootstrap bootstrap() {
        if (bootstrapped) {
            return new Bootstrap(importResult, importDocument);
        }
        importDocument = buildImportDocument();
        try {
            Files.writeString(work.resolve("platform-config.it.json"), ControlPlaneApi.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(importDocument));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        importResult = cp.post("/import", importDocument).json();
        refreshIds();
        for (String app : List.of(ORDERS, BATCH)) {
            JsonNode k = cp.post("/applications/" + id("app", app) + "/api-keys", Map.of("label", "it")).json();
            apiKeys.put(app, k.get("apiKey").asText());
            ids.put("apikey:" + app, k.get("id").asText());
        }
        bootstrapped = true;
        return new Bootstrap(importResult, importDocument);
    }

    public void refreshIds() {
        ControlPlaneApi.items(cp.get("/teams").json()).forEach(n -> ids.put("team:" + n.get("name").asText(), n.get("id").asText()));
        ControlPlaneApi.items(cp.get("/applications").json()).forEach(n -> ids.put("app:" + n.get("name").asText(), n.get("id").asText()));
        ControlPlaneApi.items(cp.get("/databases").json()).forEach(n -> ids.put("db:" + n.get("name").asText(), n.get("id").asText()));
        ControlPlaneApi.items(cp.get("/datasources").json()).forEach(n -> ids.put("ds:" + n.get("name").asText(), n.get("id").asText()));
        ControlPlaneApi.items(cp.get("/credentials").json()).forEach(n -> ids.put("cred:" + n.get("name").asText(), n.get("id").asText()));
    }

    public String id(String kind, String name) {
        String v = ids.get(kind + ":" + name);
        if (v == null) {
            throw new IllegalStateException("no id known for " + kind + " '" + name + "' (bootstrap not run?)");
        }
        return v;
    }

    private ObjectNode buildImportDocument() {
        ObjectNode doc;
        try {
            doc = (ObjectNode) ControlPlaneApi.JSON.readTree(repoRoot().resolve("deploy/bootstrap/platform-config.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        doc.put("description", "deploy/bootstrap/platform-config.json adapted by dbp-integration-tests: sales-postgres -> embedded PostgreSQL, sales current = sales-postgres");
        for (JsonNode n : doc.withArray("credentials")) {
            ObjectNode c = (ObjectNode) n;
            if (CRED_APP.equals(c.path("name").asText())) {
                c.put("provider", "INLINE");
                c.putNull("ref");
                c.put("secret", SALES_APP_PASSWORD);
            } else if (CRED_COLLECTOR.equals(c.path("name").asText())) {
                c.put("provider", "ENV");
                c.put("ref", "DBP_COLLECTOR_PASSWORD");
            }
        }
        ArrayNode dbs = doc.withArray("databases");
        ObjectNode pgNode = null;
        for (JsonNode n : dbs) {
            ObjectNode d = (ObjectNode) n;
            String name = d.path("name").asText();
            if (DB_ORACLE.equals(name)) {
                ((ObjectNode) d.get("collector")).put("enabled", false);
            } else if (DB_PG.equals(name)) {
                pgNode = d;
                d.put("host", "127.0.0.1");
                d.put("port", pg.port());
                d.put("serviceName", "sales");
                d.put("maxPhysicalConnections", 30);
                ObjectNode collector = (ObjectNode) d.get("collector");
                collector.put("enabled", true);
                collector.put("dictionaryIntervalSeconds", 600);
                collector.put("runtimeIntervalSeconds", 5);
            }
        }
        if (pgNode == null) {
            throw new IllegalStateException("platform-config.json has no database " + DB_PG);
        }
        ObjectNode alt = pgNode.deepCopy();
        alt.put("name", DB_ALT);
        alt.put("serviceName", "sales_alt");
        alt.put("description", "Second PostgreSQL database (same cluster) used by the routing-switch scenario");
        ((ObjectNode) alt.get("collector")).put("enabled", false);
        alt.putArray("tags").add("env:it");
        dbs.add(alt);
        for (JsonNode n : doc.withArray("datasources")) {
            ObjectNode ds = (ObjectNode) n;
            if (DS_SALES.equals(ds.path("name").asText())) {
                ds.put("currentDatabase", DB_PG);
                ds.putNull("targetDatabase");
                ObjectNode policy = (ObjectNode) ds.get("poolPolicy");
                policy.put("maxConnections", SALES_MAX_CONNECTIONS);
                policy.put("minIdle", 1);
                policy.put("connectionTimeoutMs", 10000);
            }
        }
        return doc;
    }

    // ------------------------------------------------------------------ gateway (scenario 2)

    public synchronized GatewayInfo ensureGateway() {
        bootstrap();
        if (gateway == null) {
            int preferred = Integer.getInteger("dbp.it.gatewayPort", 17420);
            gatewayPort = Ports.preferred(preferred);
            if (gatewayPort != preferred) {
                findings.add("gateway port " + preferred + " was busy, used " + gatewayPort);
            }
            gatewayAdminPort = Ports.free();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("DBP_CONTROL_PLANE_URL", cp.baseUrl());
            env.put("DBP_SERVICE_TOKEN", SERVICE_TOKEN);
            env.put("DBP_GATEWAY_ID", GATEWAY_ID);
            env.put("DBP_GATEWAY_PORT", String.valueOf(gatewayPort));
            env.put("DBP_GATEWAY_ADMIN_PORT", String.valueOf(gatewayAdminPort));
            env.put("DBP_GATEWAY_BIND", "127.0.0.1");
            env.put("DBP_GATEWAY_ADVERTISED_HOST", "127.0.0.1");
            env.put("DBP_CONFIG_POLL_SECONDS", "1");
            env.put("DBP_POOL_STATS_SECONDS", "2");
            env.put("DBP_HEARTBEAT_SECONDS", "2");
            env.put("DBP_TELEMETRY_FLUSH_MS", "500");
            env.put("DBP_AUTH_CACHE_SECONDS", "3");
            env.put("DBP_LOG_LEVEL", "INFO");
            gateway = ManagedProcess.start("gateway", logs, work, env, List.of(javaBin(), "-Xmx256m", "-jar", gatewayJar().toString()));
            awaitHealthy(gateway, gatewayAdminUrl() + "/health", Duration.ofSeconds(120));
        }
        return new GatewayInfo(gatewayPort, gatewayAdminPort, gatewayAdminUrl());
    }

    private String gatewayAdminUrl() {
        return "http://127.0.0.1:" + gatewayAdminPort;
    }

    public JsonNode gatewayAdmin(String path) {
        ensureGateway();
        return cp.getAbsolute(gatewayAdminUrl() + path).json();
    }

    public ManagedProcess gatewayProcess() {
        return gateway;
    }

    public ManagedProcess controlPlaneProcess() {
        return controlPlane;
    }

    public String dbpUrl(String datasource) {
        ensureGateway();
        return "jdbc:dbp://127.0.0.1:" + gatewayPort + "/" + datasource;
    }

    public String apiKey(String application) {
        bootstrap();
        String k = apiKeys.get(application);
        if (k == null) {
            throw new IllegalStateException("no api key issued for " + application);
        }
        return k;
    }

    /** Driver connection through the gateway with the api key in the URL. */
    public Connection connect(String datasource, String application) throws SQLException {
        return DriverManager.getConnection(dbpUrl(datasource) + "?apiKey=" + apiKey(application));
    }

    /** Driver connection with the api key passed as {@code password} (Spring/Hikari style). */
    public Connection connectUserPassword(String datasource, String application, String apiKey) throws SQLException {
        return DriverManager.getConnection(dbpUrl(datasource), application, apiKey);
    }

    public Connection pgSuperuser(String database) throws SQLException {
        return pg.superuser(database);
    }

    public EmbeddedPg pg() {
        return pg;
    }

    public ControlPlaneApi cp() {
        return cp;
    }

    public int controlPlanePort() {
        return cpPort;
    }

    /** Current number of sales_app client backends on database sales. */
    public int salesAppBackends() throws SQLException {
        try (Connection c = pg.superuser("postgres")) {
            return (int) Sql.queryLong(c, "SELECT count(*) FROM pg_stat_activity WHERE usename = 'sales_app' AND datname = 'sales' AND backend_type = 'client backend'");
        }
    }

    // ------------------------------------------------------------------ proxy (scenario 10)

    public Optional<Path> proxyJar() {
        return resolveJar("dbp.it.proxyJar", "proxy", List.of(), "dbp-proxy-*-all.jar");
    }

    public synchronized ProxyInfo ensureProxy() {
        bootstrap();
        if (proxy == null) {
            Path jar = proxyJar().orElseThrow(() -> new IllegalStateException("proxy shaded jar not found (-Ddbp.it.proxyJar=...)"));
            proxyAdminPort = Ports.free();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("DBP_SERVICE_TOKEN", SERVICE_TOKEN);
            env.put("DBP_PROXY_ID", PROXY_ID);
            env.put("DBP_PROXY_LISTEN_ADDRESS", "127.0.0.1");
            env.put("DBP_PROXY_ADMIN_PORT", String.valueOf(proxyAdminPort));
            env.put("DBP_HEARTBEAT_SECONDS", "1");
            env.put("DBP_CONFIG_POLL_SECONDS", "1");
            env.put("DBP_TELEMETRY_FLUSH_MS", "500");
            env.put("DBP_LOG_LEVEL", "DEBUG");
            if (Ports.isFree(5432)) {
                proxyMode = "control-plane";
                env.put("DBP_CONTROL_PLANE_URL", cp.baseUrl());
            } else {
                proxyMode = "static";
                proxyPgPort = Ports.free();
                Path yaml = work.resolve("proxy-static.yaml");
                try {
                    Files.writeString(yaml, """
                            proxyId: %s
                            listeners:
                              - name: postgres-main
                                engine: POSTGRES
                                port: %d
                                bindAddress: 127.0.0.1
                                routes:
                                  - match: sales
                                    datasource: sales
                                    datasourceId: %s
                                    databaseId: %s
                                    host: 127.0.0.1
                                    port: %d
                                    serviceName: sales
                                    rewriteServiceName: true
                            applications:
                              - name: orders-service
                                id: %s
                                identityRules:
                                  serviceAliases: [orders-service]
                                  pgApplicationNames: [orders-service]
                            """.formatted(PROXY_ID, proxyPgPort, id("ds", DS_SALES), id("db", DB_PG), pg.port(), id("app", ORDERS)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                env.put("DBP_PROXY_CONFIG", yaml.toString());
                findings.add("port 5432 busy: proxy started in static mode on port " + proxyPgPort + " (control-plane mode pins PostgreSQL listeners to 5432)");
            }
            proxy = ManagedProcess.start("proxy", logs, work, env, List.of(javaBin(), "-Xmx160m", "-jar", jar.toString()));
            String adminUrl = "http://127.0.0.1:" + proxyAdminPort;
            JsonNode health = Await.until("proxy health with a running POSTGRES listener", Duration.ofSeconds(90), () -> {
                if (!proxy.isAlive()) {
                    throw new Await.Fatal("proxy exited:\n" + proxy.tail(40));
                }
                try {
                    ControlPlaneApi.Response r = cp.getAbsolute(adminUrl + "/health");
                    if (!r.ok()) {
                        return Optional.empty();
                    }
                    boolean pgRunning = ControlPlaneApi.stream(r.body().path("listeners"))
                            .anyMatch(l -> "POSTGRES".equals(l.path("engine").asText()) && l.path("running").asBoolean());
                    return pgRunning ? Optional.of(r.body()) : Optional.empty();
                } catch (UncheckedIOException e) {
                    return Optional.empty();
                }
            });
            proxyPgPort = ControlPlaneApi.stream(health.path("listeners")).filter(l -> "POSTGRES".equals(l.path("engine").asText()))
                    .findFirst().map(l -> l.path("port").asInt()).orElse(proxyPgPort);
        }
        return new ProxyInfo(proxyMode, proxyPgPort, proxyAdminPort, "http://127.0.0.1:" + proxyAdminPort);
    }

    // ------------------------------------------------------------------ H2 second engine (scenario 8)

    public synchronized H2Info ensureH2Gateway() {
        if (h2Gateway == null) {
            try {
                h2Keeper = DriverManager.getConnection("jdbc:h2:mem:salesh2;MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
                Sql.exec(h2Keeper,
                        "CREATE TABLE customer (id NUMBER(19) PRIMARY KEY, email VARCHAR2(200) NOT NULL, first_name VARCHAR2(100),"
                                + " last_name VARCHAR2(100), country_code CHAR(2), status VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL)",
                        "INSERT INTO customer (id, email, first_name, last_name, country_code) VALUES (1, 'h2-1@example.org', 'Hedda', 'Two', 'DE')",
                        "INSERT INTO customer (id, email, first_name, last_name, country_code) VALUES (2, 'h2-2@example.org', 'Hans', 'Two', 'AT')");
                h2TcpPort = Ports.free();
                h2Server = Server.createTcpServer("-tcpPort", String.valueOf(h2TcpPort), "-ifNotExists").start();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot start H2: " + e.getMessage(), e);
            }
            h2GatewayPort = Ports.free();
            h2GatewayAdminPort = Ports.free();
            Path yaml = work.resolve("gateway-h2.yaml");
            try {
                Files.writeString(yaml, """
                        gatewayId: %s
                        datasources:
                          - name: %s
                            engine: H2
                            jdbcUrl: jdbc:h2:tcp://127.0.0.1:%d/mem:salesh2;MODE=Oracle
                            username: sa
                            password: ""
                            poolMode: TRANSACTION
                            maxConnections: 2
                            minIdle: 0
                        applications:
                          - name: %s
                            apiKey: %s
                            datasources: [%s]
                        """.formatted(H2_GATEWAY_ID, H2_DATASOURCE, h2TcpPort, BATCH, H2_API_KEY, H2_DATASOURCE));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Map<String, String> env = new LinkedHashMap<>();
            env.put("DBP_GATEWAY_CONFIG", yaml.toString());
            env.put("DBP_GATEWAY_ID", H2_GATEWAY_ID);
            env.put("DBP_GATEWAY_PORT", String.valueOf(h2GatewayPort));
            env.put("DBP_GATEWAY_ADMIN_PORT", String.valueOf(h2GatewayAdminPort));
            env.put("DBP_GATEWAY_BIND", "127.0.0.1");
            env.put("DBP_GATEWAY_ADVERTISED_HOST", "127.0.0.1");
            h2Gateway = ManagedProcess.start("gateway-h2", logs, work, env, List.of(javaBin(), "-Xmx192m", "-jar", gatewayJar().toString()));
            awaitHealthy(h2Gateway, "http://127.0.0.1:" + h2GatewayAdminPort + "/health", Duration.ofSeconds(120));
        }
        return new H2Info(h2GatewayPort, h2GatewayAdminPort, h2TcpPort, "jdbc:dbp://127.0.0.1:" + h2GatewayPort + "/" + H2_DATASOURCE,
                "http://127.0.0.1:" + h2GatewayAdminPort);
    }

    // ------------------------------------------------------------------ shutdown

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOG.info("stopping the stack");
        stopQuietly(h2Gateway);
        stopQuietly(proxy);
        stopQuietly(gateway);
        stopQuietly(controlPlane);
        if (h2Server != null) {
            try {
                h2Server.stop();
            } catch (RuntimeException e) {
                LOG.warn("h2 server stop: {}", e.toString());
            }
        }
        if (h2Keeper != null) {
            try {
                h2Keeper.close();
            } catch (SQLException ignored) {
                // closing
            }
        }
        if (pg != null) {
            try {
                pg.close();
            } catch (RuntimeException e) {
                LOG.warn("postgres stop: {}", e.toString());
            }
        }
        long leftovers = ProcessHandle.current().descendants().count();
        if (leftovers > 0) {
            LOG.warn("{} child processes still alive after shutdown, killing", leftovers);
            ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
            findings.add(leftovers + " child processes had to be killed at shutdown");
        } else {
            LOG.info("no child processes left");
        }
        current = null;
    }

    private static void stopQuietly(ManagedProcess p) {
        if (p != null) {
            try {
                p.close();
            } catch (RuntimeException e) {
                LOG.warn("{} stop: {}", p.name(), e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void awaitHealthy(ManagedProcess process, String url, Duration timeout) {
        Await.until(process.name() + " health at " + url, timeout, () -> {
            if (!process.isAlive()) {
                throw new Await.Fatal(process.name() + " exited during startup; log tail:\n" + process.tail(60));
            }
            try {
                ControlPlaneApi.Response r = cp == null ? null : cp.getAbsolute(url);
                if (r != null && r.ok() && "UP".equals(r.body().path("status").asText())) {
                    return Optional.of(r.body());
                }
                return Optional.empty();
            } catch (UncheckedIOException e) {
                return Optional.empty();
            }
        });
    }

    private Path gatewayJar() {
        return resolveJar("dbp.it.gatewayJar", "gateway", List.of(
                Path.of(System.getProperty("user.home"), ".m2/repository/org/dbplatform/dbp-gateway/0.1.0-SNAPSHOT/dbp-gateway-0.1.0-SNAPSHOT-all.jar"),
                repoRoot().resolve("dbp-gateway/target/dbp-gateway-0.1.0-SNAPSHOT-all.jar")), "dbp-gateway-*-all.jar")
                .orElseThrow(() -> new IllegalStateException("gateway shaded jar not found (-Ddbp.it.gatewayJar=...)"));
    }

    /** The configured path, then the candidates, then a glob in the sibling module's target directory. */
    private static Optional<Path> resolveJar(String property, String label, List<Path> candidates, String glob) {
        String p = System.getProperty(property);
        List<Path> all = new ArrayList<>();
        if (p != null && !p.isBlank()) {
            all.add(Path.of(p));
        }
        all.addAll(candidates);
        for (Path c : all) {
            if (Files.isRegularFile(c) && sizeOf(c) > 100_000) {
                return Optional.of(c.toAbsolutePath());
            }
        }
        for (Path dir : List.of(repoRoot().resolve("dbp-" + label.replace(' ', '-') + "/target"), repoRoot().resolve("dbp-proxy/target"))) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    Optional<Path> hit = s.filter(f -> f.getFileSystem().getPathMatcher("glob:" + glob).matches(f.getFileName()))
                            .filter(f -> sizeOf(f) > 100_000).max(Comparator.comparingLong(Stack::sizeOf));
                    if (hit.isPresent()) {
                        return hit;
                    }
                } catch (IOException ignored) {
                    // next
                }
            }
        }
        LOG.warn("{} jar not found (property {}={}, candidates {})", label, property, p, candidates);
        return Optional.empty();
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String javaBin() {
        return ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
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
        } catch (IOException ignored) {
            // best effort
        }
    }
}
