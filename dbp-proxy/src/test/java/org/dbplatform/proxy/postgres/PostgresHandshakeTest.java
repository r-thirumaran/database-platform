package org.dbplatform.proxy.postgres;

import org.dbplatform.proxy.ProxyApp;
import org.dbplatform.proxy.config.ApplicationConfig;
import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.IdentityRules;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.config.RouteConfig;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.support.Await;
import org.dbplatform.proxy.support.RecordingEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the PostgreSQL startup handshake with raw sockets against a scripted fake backend (the real
 * driver / embedded PostgreSQL path is {@link PostgresEndToEndTest}): deadline, SSL request cap, strict
 * aliases, unknown-application quota and byte-exact rewriting of the startup message.
 */
class PostgresHandshakeTest {
    private ProxyApp app;
    private final RecordingEvents events = new RecordingEvents();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
    }

    /** Accepts, reads the StartupMessage, answers AuthenticationOk + ReadyForQuery and echoes until EOF. */
    static final class FakePgBackend implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        final List<byte[]> startups = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        FakePgBackend() throws IOException {
            Thread.ofVirtual().start(() -> {
                while (running) {
                    try {
                        Socket s = server.accept();
                        Thread.ofVirtual().start(() -> serve(s));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        private void serve(Socket s) {
            try (s) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                PgStartupMessage m = PgStartupMessage.read(in);
                if (m == null) {
                    return;
                }
                startups.add(m.raw());
                OutputStream out = s.getOutputStream();
                out.write(new byte[] {'R', 0, 0, 0, 8, 0, 0, 0, 0});   // AuthenticationOk
                out.write(new byte[] {'Z', 0, 0, 0, 5, 'I'});          // ReadyForQuery
                out.flush();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // client gone
            }
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
        }
    }

    private static ProxySettings defaultSettings() {
        return ProxySettings.defaults().withListenAddress("127.0.0.1").withTimeouts(0, 2000, 5000);
    }

    private int startProxy(int backendPort, ProxySettings settings) throws IOException {
        RouteConfig sales = new RouteConfig("sales", null, "ds-sales", "db-pg", "127.0.0.1", backendPort, "postgres", true);
        ListenerConfig l = new ListenerConfig("postgres-main", Engine.POSTGRES, "127.0.0.1", 0, null, List.of(sales), null);
        ApplicationConfig orders = new ApplicationConfig("app-1", "orders-service", "team-1",
                new IdentityRules(null, null, null, List.of("orders-service"), List.of("orders-service")));
        ProxyConfigDocument cfg = new ProxyConfigDocument(1, "proxy-test", List.of(l), List.of(orders), List.of(), List.of());
        app = ProxyApp.startEmbedded(settings, cfg, events, false);
        return app.server().listener("postgres-main").boundPort();
    }

    private static Socket client(int port) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5000);
        return s;
    }

    private static byte[] startup(String user, String database, String applicationName) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("user", user);
        p.put("database", database);
        if (applicationName != null) {
            p.put("application_name", applicationName);
        }
        return PgStartupMessage.build(PgStartupMessage.PROTOCOL_3_0, p).raw();
    }

    private static byte[] sslRequest() {
        byte[] b = new byte[8];
        PgStartupMessage.putI32(b, 0, 8);
        PgStartupMessage.putI32(b, 4, PgStartupMessage.SSL_REQUEST);
        return b;
    }

    /** First backend-style message from the proxy: type byte + fields; for 'E' the SQLSTATE ('C') and message ('M'). */
    record Reply(char type, Map<Character, String> fields) {
        String sqlState() {
            return fields.get('C');
        }

        String message() {
            return fields.get('M');
        }
    }

    private static Reply readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            return new Reply('\0', Map.of());
        }
        byte[] len = in.readNBytes(4);
        int n = PgStartupMessage.i32(len, 0) - 4;
        byte[] body = in.readNBytes(n);
        Map<Character, String> fields = new LinkedHashMap<>();
        if (type == 'E') {
            int pos = 0;
            while (pos < body.length && body[pos] != 0) {
                char f = (char) body[pos++];
                int end = pos;
                while (body[end] != 0) {
                    end++;
                }
                fields.put(f, new String(body, pos, end - pos, StandardCharsets.UTF_8));
                pos = end + 1;
            }
        }
        return new Reply((char) type, fields);
    }

    @Test
    void stalledClientIsDroppedAtTheHandshakeDeadlineAndItsSlotReleased() throws Exception {
        try (FakePgBackend backend = new FakePgBackend()) {
            int port = startProxy(backend.port(), defaultSettings().withTimeouts(0, 2000, 700));
            long t0 = System.nanoTime();
            try (Socket s = client(port)) {
                s.getOutputStream().write(new byte[] {0, 0, 0, 41}); // a plausible length, then nothing
                s.getOutputStream().flush();
                int r;
                try {
                    r = s.getInputStream().read();
                } catch (IOException reset) {
                    r = -1;
                }
                assertThat(r).isEqualTo(-1);
                assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(500L, 4000L);
            }
            Await.until(3000, () -> app.server().listener("postgres-main").inFlight() == 0, "in-flight slot released");
            assertThat(app.registry().size()).isZero();
            assertThat(backend.startups).isEmpty();
        }
    }

    @Test
    void atMostTwoEncryptionRequestsAreAnsweredBeforeTheClientIsRejected() throws Exception {
        try (FakePgBackend backend = new FakePgBackend()) {
            int port = startProxy(backend.port(), defaultSettings());
            try (Socket s = client(port)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                out.write(sslRequest());
                out.flush();
                assertThat(in.read()).isEqualTo('N');
                out.write(sslRequest());
                out.flush();
                assertThat(in.read()).isEqualTo('N');
                out.write(sslRequest());
                out.flush();
                int r;
                try {
                    r = in.read();
                } catch (IOException reset) {
                    r = -1;
                }
                assertThat(r).as("third request: connection dropped").isEqualTo(-1);
            }
            Await.until(3000, () -> app.metrics().scrape().contains("reason=\"protocol\""), "protocol refusal counted");
            assertThat(backend.startups).isEmpty();
        }
    }

    @Test
    void strictAliasesRefuseUndeclaredAliasesWithSqlState3D000() throws Exception {
        try (FakePgBackend backend = new FakePgBackend()) {
            int port = startProxy(backend.port(), defaultSettings().withStrictAliases(true));
            try (Socket s = client(port)) {
                s.getOutputStream().write(startup("app", "sales.nobody", "nobody\nFAKE LOG LINE"));
                s.getOutputStream().flush();
                Reply r = readReply(s.getInputStream());
                assertThat(r.type()).isEqualTo('E');
                assertThat(r.sqlState()).isEqualTo("3D000");
                assertThat(r.message()).isEqualTo("database \"sales.nobody\": application alias \"nobody\" is not declared");
            }
            Await.until(3000, () -> events.ofType("REFUSED").size() == 1, "REFUSED event");
            RecordingEvents.Event refused = events.ofType("REFUSED").get(0);
            assertThat(refused.reason()).isEqualTo("undeclared application alias 'nobody' in database 'sales.nobody' (DBP_PROXY_STRICT_ALIASES=true)");
            assertThat(refused.connection().get("program")).as("control characters never reach events").isEqualTo("nobody?FAKE LOG LINE");
            assertThat(refused.connection().get("application")).isEqualTo("nobody");

            // declared alias and the bare logical name still connect
            try (Socket s = client(port)) {
                s.getOutputStream().write(startup("app", "sales.orders-service", null));
                s.getOutputStream().flush();
                assertThat(readReply(s.getInputStream()).type()).isEqualTo('R');
            }
            try (Socket s = client(port)) {
                s.getOutputStream().write(startup("app", "sales", null));
                s.getOutputStream().flush();
                assertThat(readReply(s.getInputStream()).type()).isEqualTo('R');
            }
            Await.until(3000, () -> backend.startups.size() == 2, "two startups reached the backend");
        }
    }

    @Test
    void unknownApplicationQuotaRefusesWith53300() throws Exception {
        try (FakePgBackend backend = new FakePgBackend()) {
            int port = startProxy(backend.port(), defaultSettings().withUnknownAppMaxConnections(1));
            try (Socket first = client(port)) {
                first.getOutputStream().write(startup("app", "sales", "anonymous-tool"));
                first.getOutputStream().flush();
                assertThat(readReply(first.getInputStream()).type()).isEqualTo('R');
                try (Socket second = client(port)) {
                    second.getOutputStream().write(startup("app", "sales", "anonymous-tool"));
                    second.getOutputStream().flush();
                    Reply r = readReply(second.getInputStream());
                    assertThat(r.sqlState()).isEqualTo("53300");
                    assertThat(r.message()).contains("unknown-application quota exceeded: sales 1/1");
                }
                try (Socket known = client(port)) {
                    known.getOutputStream().write(startup("app", "sales.orders-service", null));
                    known.getOutputStream().flush();
                    assertThat(readReply(known.getInputStream()).type()).as("registered application unaffected").isEqualTo('R');
                }
            }
            Await.until(3000, () -> app.registry().size() == 0, "slots released");
        }
    }

    @Test
    void databaseRewriteKeepsEveryOtherStartupByteIncludingNonUtf8Users() throws Exception {
        try (FakePgBackend backend = new FakePgBackend()) {
            int port = startProxy(backend.port(), defaultSettings());
            // LATIN1 client: user "rené" is sent as 72 65 6E E9 — not valid UTF-8
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write("user\0ren".getBytes(StandardCharsets.ISO_8859_1));
            body.write(0xE9);
            body.write(0);
            body.write("database\0sales.orders-service\0client_encoding\0LATIN1\0\0".getBytes(StandardCharsets.ISO_8859_1));
            byte[] raw = new byte[8 + body.size()];
            PgStartupMessage.putI32(raw, 0, raw.length);
            PgStartupMessage.putI32(raw, 4, PgStartupMessage.PROTOCOL_3_0);
            System.arraycopy(body.toByteArray(), 0, raw, 8, body.size());

            try (Socket s = client(port)) {
                s.getOutputStream().write(raw);
                s.getOutputStream().flush();
                assertThat(readReply(s.getInputStream()).type()).isEqualTo('R');
                Await.until(3000, () -> backend.startups.size() == 1, "startup forwarded");
                byte[] forwarded = backend.startups.get(0);

                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write("user\0ren".getBytes(StandardCharsets.ISO_8859_1));
                expected.write(0xE9);
                expected.write(0);
                expected.write("database\0postgres\0client_encoding\0LATIN1\0\0".getBytes(StandardCharsets.ISO_8859_1));
                assertThat(PgStartupMessage.i32(forwarded, 0)).isEqualTo(forwarded.length);
                assertThat(java.util.Arrays.copyOfRange(forwarded, 8, forwarded.length)).isEqualTo(expected.toByteArray());

                LiveConnection live = app.registry().all().iterator().next();
                assertThat(live.dbUser()).as("display decoding only").isEqualTo("ren�");
                assertThat(live.requestedService()).isEqualTo("sales.orders-service");
                assertThat(live.resolvedService()).isEqualTo("postgres");
                assertThat(live.application()).isEqualTo("orders-service");
            }
        }
    }
}
