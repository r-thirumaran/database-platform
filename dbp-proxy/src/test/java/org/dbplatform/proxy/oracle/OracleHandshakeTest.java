package org.dbplatform.proxy.oracle;

import org.dbplatform.proxy.ProxyApp;
import org.dbplatform.proxy.config.ApplicationConfig;
import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.IdentityRules;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.config.QuotaConfig;
import org.dbplatform.proxy.config.RouteConfig;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.support.Await;
import org.dbplatform.proxy.support.RecordingEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the Oracle handshake state machine against scripted fake backends. */
class OracleHandshakeTest {
    static final String CLIENT = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy)(PORT=1521))"
            + "(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=JDBC Thin Client)(HOST=orders-7f9c)(USER=app))))";

    private ProxyApp app;
    private final RecordingEvents events = new RecordingEvents();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
    }

    private int startProxy(String backendHost, int backendPort, int quota) throws IOException {
        RouteConfig sales = new RouteConfig("sales", null, null, "db-1", backendHost, backendPort, "FREEPDB1", true);
        ListenerConfig l = new ListenerConfig("oracle-main", Engine.ORACLE, "127.0.0.1", 0, null, List.of(sales), null);
        ApplicationConfig orders = new ApplicationConfig("app-1", "orders-service", "team-1",
                new IdentityRules(null, null, null, List.of("orders-service"), null));
        ProxyConfigDocument cfg = new ProxyConfigDocument(1, "proxy-test", List.of(l), List.of(orders),
                quota > 0 ? List.of(new QuotaConfig("app-1", null, "sales", null, quota)) : List.of(), List.of());
        ProxySettings settings = ProxySettings.defaults().withListenAddress("127.0.0.1").withTimeouts(0, 2000, 5000);
        app = ProxyApp.startEmbedded(settings, cfg, events, false);
        return app.server().listener("oracle-main").boundPort();
    }

    private static Socket client(int port) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5000);
        return s;
    }

    private static TnsPacket send(Socket s, byte[] bytes) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(bytes);
        out.flush();
        return TnsPacket.read(new BufferedInputStream(s.getInputStream()));
    }

    @Test
    void acceptPathRewritesServiceAndAddressThenPumpsTransparently() throws Exception {
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            FakeTnsServer.Session session = s;
            TnsConnectPacket c = null;
            // the script instance needs the server to record; handled via the holder below
            c = OracleConnectionHandler.readConnectRequest(session.in);
            RecordHolder.last = c;
            RecordHolder.port = session.remotePort();
            FakeTnsServer.acceptAndEcho(session);
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 0);
            try (Socket s = client(port)) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                s.getOutputStream().write(TnsTestPackets.connect(318, CLIENT));
                s.getOutputStream().flush();
                TnsPacket reply = TnsPacket.read(in);
                assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_ACCEPT);

                assertThat(RecordHolder.last.connectData()).isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=127.0.0.1)(PORT=" + backend.port() + "))"
                        + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(CID=(PROGRAM=JDBC Thin Client)(HOST=orders-7f9c)(USER=app))))");
                assertThat(RecordHolder.last.version()).isEqualTo(318);
                assertThat(RecordHolder.last.usesDeferredForm()).isFalse();

                // data path is transparent: anything we send comes back (the fake echoes)
                byte[] marker = TnsPacket.create(TnsPacket.TYPE_MARKER, 0, new byte[] {1, 0, 2}).bytes();
                s.getOutputStream().write(marker);
                s.getOutputStream().write(TnsTestPackets.data("hello"));
                s.getOutputStream().flush();
                assertThat(TnsPacket.read(in).type()).isEqualTo(TnsPacket.TYPE_MARKER);
                assertThat(TnsTestPackets.dataText(TnsPacket.read(in))).isEqualTo("hello");

                Await.until(3000, () -> app.registry().size() == 1, "registry entry");
                LiveConnection live = app.registry().all().iterator().next();
                assertThat(live.state()).isEqualTo(LiveConnection.State.ESTABLISHED);
                assertThat(live.application()).isEqualTo("orders-service");
                assertThat(live.applicationId()).isEqualTo("app-1");
                assertThat(live.identitySource().name()).isEqualTo("SERVICE_ALIAS");
                assertThat(live.datasource()).isEqualTo("sales");
                assertThat(live.requestedService()).isEqualTo("sales.orders-service");
                assertThat(live.resolvedService()).isEqualTo("FREEPDB1");
                assertThat(live.program()).isEqualTo("JDBC Thin Client");
                assertThat(live.clientHost()).isEqualTo("orders-7f9c");
                assertThat(live.osUser()).isEqualTo("app");
                assertThat(live.proxyLocalPort()).isEqualTo(RecordHolder.port);
                assertThat(live.backendPort()).isEqualTo(backend.port());
            }
            Await.until(3000, () -> !events.ofType("CLOSE").isEmpty(), "CLOSE event");
            assertThat(events.ofType("OPEN")).hasSize(1);
            assertThat(events.ofType("CLOSE").get(0).reason()).isEqualTo("client closed");
            assertThat(app.registry().size()).isZero();
            assertThat(backend.errors).isEmpty();
        }
    }

    /** Static holder for what the fake backend observed (scripts are lambdas). */
    static final class RecordHolder {
        static volatile TnsConnectPacket last;
        static volatile int port;
    }

    @Test
    void deferredConnectStringIsForwardedInDeferredForm() throws Exception {
        String longClient = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy.very.long.host.name.example.org)(PORT=1521))"
                + "(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(SERVER=DEDICATED)"
                + "(CID=(PROGRAM=JDBC Thin Client with a rather long program name for testing)(HOST=orders-service-7f9c4d5b6-abcde.default.svc.cluster.local)(USER=application-user))))";
        assertThat(longClient.length()).isGreaterThan(TnsConnectPacket.INLINE_LIMIT);
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            RecordHolder.last = OracleConnectionHandler.readConnectRequest(s.in);
            FakeTnsServer.acceptAndEcho(s);
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 0);
            try (Socket s = client(port)) {
                TnsPacket reply = send(s, TnsTestPackets.connect(315, longClient));
                assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                assertThat(RecordHolder.last.usesDeferredForm()).isTrue();
                assertThat(RecordHolder.last.version()).isEqualTo(315);
                assertThat(RecordHolder.last.connectDataOffset()).isEqualTo(58);
                assertThat(RecordHolder.last.connectData()).contains("(SERVICE_NAME=FREEPDB1)")
                        .contains("(HOST=127.0.0.1)(PORT=" + backend.port() + ")")
                        .contains("(USER=application-user)");
            }
            assertThat(backend.errors).isEmpty();
        }
    }

    @Test
    void redirectIsFollowedByTheProxyAndNeverSeenByTheClient() throws Exception {
        try (FakeTnsServer target = new FakeTnsServer(s -> {
            TnsConnectPacket c = OracleConnectionHandler.readConnectRequest(s.in);
            RecordHolder.last = c;
            RecordHolder.port = s.remotePort();
            FakeTnsServer.acceptAndEcho(s);
        })) {
            String replacement = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=127.0.0.1)(PORT=" + target.port() + "))"
                    + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(SERVER=DEDICATED)(CID=(PROGRAM=JDBC Thin Client)(HOST=orders-7f9c)(USER=app))))";
            try (FakeTnsServer listener = new FakeTnsServer(s -> {
                OracleConnectionHandler.readConnectRequest(s.in);
                s.write(TnsRedirectPacket.build("(ADDRESS=(PROTOCOL=tcp)(HOST=127.0.0.1)(PORT=" + target.port() + "))", replacement));
            })) {
                int port = startProxy("127.0.0.1", listener.port(), 0);
                try (Socket s = client(port)) {
                    InputStream in = new BufferedInputStream(s.getInputStream());
                    s.getOutputStream().write(TnsTestPackets.connect(318, CLIENT));
                    s.getOutputStream().flush();
                    TnsPacket reply = TnsPacket.read(in);
                    assertThat(reply.type()).as("client sees ACCEPT, not the REDIRECT").isEqualTo(TnsPacket.TYPE_ACCEPT);
                    assertThat(RecordHolder.last.connectData()).isEqualTo(replacement);
                    s.getOutputStream().write(TnsTestPackets.data("after-redirect"));
                    s.getOutputStream().flush();
                    assertThat(TnsTestPackets.dataText(TnsPacket.read(in))).isEqualTo("after-redirect");

                    LiveConnection live = app.registry().all().iterator().next();
                    assertThat(live.backendPort()).as("registry points at the redirect target").isEqualTo(target.port());
                    assertThat(live.proxyLocalPort()).as("correlation key is the port the final backend sees").isEqualTo(RecordHolder.port);
                }
                assertThat(listener.errors).isEmpty();
                assertThat(target.errors).isEmpty();
            }
        }
    }

    @Test
    void redirectWithoutReplacementDataResendsRewrittenString() throws Exception {
        try (FakeTnsServer target = new FakeTnsServer(s -> {
            RecordHolder.last = OracleConnectionHandler.readConnectRequest(s.in);
            FakeTnsServer.acceptAndEcho(s);
        }); FakeTnsServer listener = new FakeTnsServer(s -> {
            OracleConnectionHandler.readConnectRequest(s.in);
            s.write(TnsRedirectPacket.build("(ADDRESS=(PROTOCOL=tcp)(HOST=127.0.0.1)(PORT=" + target.port() + "))", null));
        })) {
            int port = startProxy("127.0.0.1", listener.port(), 0);
            try (Socket s = client(port)) {
                assertThat(send(s, TnsTestPackets.connect(318, CLIENT)).type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                assertThat(RecordHolder.last.connectData()).contains("(SERVICE_NAME=FREEPDB1)")
                        .contains("(HOST=127.0.0.1)(PORT=" + listener.port() + ")");
            }
        }
    }

    @Test
    void resendIsForwardedAndTheClientsSecondConnectIsRewrittenToo() throws Exception {
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            TnsConnectPacket first = OracleConnectionHandler.readConnectRequest(s.in);
            s.write(TnsTestPackets.resend());
            TnsConnectPacket second = OracleConnectionHandler.readConnectRequest(s.in);
            RecordHolder.last = second;
            if (!first.connectData().equals(second.connectData())) {
                throw new IOException("resent connect data differs");
            }
            FakeTnsServer.acceptAndEcho(s);
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 0);
            try (Socket s = client(port)) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                s.getOutputStream().write(TnsTestPackets.connect(318, CLIENT));
                s.getOutputStream().flush();
                assertThat(TnsPacket.read(in).type()).isEqualTo(TnsPacket.TYPE_RESEND);
                s.getOutputStream().write(TnsTestPackets.connect(318, CLIENT));
                s.getOutputStream().flush();
                assertThat(TnsPacket.read(in).type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                assertThat(RecordHolder.last.connectData()).contains("(SERVICE_NAME=FREEPDB1)");
            }
            assertThat(backend.errors).isEmpty();
        }
    }

    @Test
    void backendRefuseIsForwardedAndReportedAsBackendFailed() throws Exception {
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            OracleConnectionHandler.readConnectRequest(s.in);
            s.write(TnsRefusePacket.build(12514));
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 0);
            try (Socket s = client(port)) {
                TnsPacket reply = send(s, TnsTestPackets.connect(318, CLIENT));
                assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_REFUSE);
                assertThat(TnsRefusePacket.errorCode(reply)).isEqualTo(12514);
                assertThat(s.getInputStream().read()).isEqualTo(-1);
            }
            Await.until(3000, () -> !events.ofType("BACKEND_FAILED").isEmpty(), "BACKEND_FAILED event");
            assertThat(events.ofType("BACKEND_FAILED").get(0).reason()).contains("ORA-12514");
            assertThat(events.ofType("OPEN")).isEmpty();
        }
    }

    @Test
    void unknownServiceQuotaAndBackendDownProduceTheRightRefuseCodes() throws Exception {
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            OracleConnectionHandler.readConnectRequest(s.in);
            FakeTnsServer.acceptAndEcho(s);
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 1);

            try (Socket s = client(port)) {
                TnsPacket reply = send(s, TnsTestPackets.connect(318, CLIENT.replace("sales.orders-service", "nosuch")));
                assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_REFUSE);
                assertThat(TnsRefusePacket.errorCode(reply)).isEqualTo(12514);
            }
            Await.until(3000, () -> events.ofType("REFUSED").size() == 1, "REFUSED event");
            assertThat(events.ofType("REFUSED").get(0).reason()).contains("unknown service 'nosuch'");

            try (Socket first = client(port)) {
                assertThat(send(first, TnsTestPackets.connect(318, CLIENT)).type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                try (Socket second = client(port)) {
                    TnsPacket reply = send(second, TnsTestPackets.connect(318, CLIENT));
                    assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_REFUSE);
                    assertThat(TnsRefusePacket.errorCode(reply)).isEqualTo(12516);
                }
                Await.until(3000, () -> events.ofType("REFUSED").size() == 2, "second REFUSED event");
                assertThat(events.ofType("REFUSED").get(1).reason()).isEqualTo("quota exceeded: orders-service/sales 1/1");
            }
            Await.until(3000, () -> app.registry().size() == 0, "slot released");

            // identity by CID PROGRAM when no alias is used, and quota still applies to the resolved app
            String noAlias = CLIENT.replace("sales.orders-service", "sales");
            try (Socket s = client(port)) {
                assertThat(send(s, TnsTestPackets.connect(318, noAlias)).type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                LiveConnection live = app.registry().all().iterator().next();
                assertThat(live.identitySource().name()).isEqualTo("NONE");
                assertThat(live.application()).isEqualTo("unknown");
                assertThat(live.resolvedService()).isEqualTo("FREEPDB1");
            }
        }

        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        app.close();
        events.clear();
        int port = startProxy("127.0.0.1", deadPort, 0);
        try (Socket s = client(port)) {
            TnsPacket reply = send(s, TnsTestPackets.connect(318, CLIENT));
            assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_REFUSE);
            assertThat(TnsRefusePacket.errorCode(reply)).isEqualTo(12541);
        }
        Await.until(3000, () -> !events.ofType("BACKEND_FAILED").isEmpty(), "BACKEND_FAILED event");
        assertThat(events.ofType("BACKEND_FAILED").get(0).reason()).startsWith("connect to 127.0.0.1:" + deadPort + " failed");
        assertThat(app.registry().size()).isZero();
    }

    @Test
    void listenerCapIsEnforcedAndConfigHotReloadKeepsConnections() throws Exception {
        try (FakeTnsServer backend = new FakeTnsServer(s -> {
            OracleConnectionHandler.readConnectRequest(s.in);
            FakeTnsServer.acceptAndEcho(s);
        })) {
            int port = startProxy("127.0.0.1", backend.port(), 0);
            ListenerConfig old = app.server().current().listeners().get(0);
            ListenerConfig capped = new ListenerConfig(old.name(), old.engine(), old.bindAddress(), 0, 1, old.routes(), old.defaultRoute());
            app.server().apply(new ProxyConfigDocument(2, "proxy-test", List.of(capped), app.server().current().applications(), List.of(), List.of()));

            try (Socket first = client(port)) {
                assertThat(send(first, TnsTestPackets.connect(318, CLIENT)).type()).isEqualTo(TnsPacket.TYPE_ACCEPT);
                try (Socket second = client(port)) {
                    TnsPacket reply = send(second, TnsTestPackets.connect(318, CLIENT));
                    assertThat(reply.type()).isEqualTo(TnsPacket.TYPE_REFUSE);
                    assertThat(TnsRefusePacket.errorCode(reply)).isEqualTo(12516);
                }
                // reload with a new route table on the same port: the established connection survives
                RouteConfig other = new RouteConfig("inventory", null, null, null, "127.0.0.1", backend.port(), "INVPDB", true);
                ListenerConfig updated = new ListenerConfig(old.name(), old.engine(), old.bindAddress(), 0, 10, List.of(other), null);
                app.server().apply(new ProxyConfigDocument(3, "proxy-test", List.of(updated), List.of(), List.of(), List.of()));
                assertThat(app.server().listener("oracle-main").boundPort()).isEqualTo(port);
                first.getOutputStream().write(TnsTestPackets.data("still-alive"));
                first.getOutputStream().flush();
                assertThat(TnsTestPackets.dataText(TnsPacket.read(new BufferedInputStream(first.getInputStream())))).isEqualTo("still-alive");

                try (Socket third = client(port)) {
                    TnsPacket reply = send(third, TnsTestPackets.connect(318, CLIENT));
                    assertThat(TnsRefusePacket.errorCode(reply)).as("old route is gone").isEqualTo(12514);
                }
            }
        }
    }
}
