package org.dbplatform.proxy.net;

import org.dbplatform.proxy.ProxyApp;
import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.config.RouteConfig;
import org.dbplatform.proxy.metrics.ProxyMetrics;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.QuotaManager;
import org.dbplatform.proxy.support.Await;
import org.dbplatform.proxy.support.FakeControlPlane;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Listener bind failures and dead accept loops are repaired on the next poll instead of staying down until a restart. */
class ProxyServerTest {

    private static ProxyConfigDocument configOn(int port) {
        RouteConfig r = new RouteConfig("sales", null, null, null, "127.0.0.1", 1, "x", false);
        ListenerConfig l = new ListenerConfig("tcp-main", Engine.TCP, "127.0.0.1", port, null, List.of(), r);
        return new ProxyConfigDocument(1, "p", List.of(l), List.of(), List.of(), List.of());
    }

    private static ProxyServer newServer() {
        ConnectionRegistry registry = new ConnectionRegistry();
        ProxySettings settings = ProxySettings.defaults().withListenAddress("127.0.0.1");
        return new ProxyServer(new ProxyRuntime(settings, registry, new QuotaManager(registry), new ProxyMetrics(registry), null));
    }

    private static String get(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void failedBindIsRetriedByRepairOnceThePortIsFree() throws Exception {
        try (ServerSocket blocker = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = blocker.getLocalPort();
            try (ProxyServer server = newServer()) {
                server.apply(configOn(port));
                assertThat(server.listenerErrors()).containsKey("tcp-main");
                assertThat(server.listenerErrors().get("tcp-main")).contains("cannot bind 127.0.0.1:" + port);
                assertThat(server.listener("tcp-main")).isNull();
                assertThat(server.needsRepair()).isTrue();

                assertThat(server.repair()).as("still blocked").isFalse();
                assertThat(server.listenerErrors()).containsKey("tcp-main");

                blocker.close();
                assertThat(server.repair()).isTrue();
                assertThat(server.listenerErrors()).isEmpty();
                assertThat(server.needsRepair()).isFalse();
                assertThat(server.listener("tcp-main").isRunning()).isTrue();
                assertThat(server.listener("tcp-main").boundPort()).isEqualTo(port);
                assertThat(server.repair()).as("nothing to do when healthy").isFalse();
                try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    assertThat(s.isConnected()).isTrue();
                }
            }
        }
    }

    @Test
    void deadAcceptLoopIsRecreatedByRepair() throws Exception {
        try (ProxyServer server = newServer()) {
            server.apply(configOn(0));
            ListenerRuntime original = server.listener("tcp-main");
            assertThat(original.isRunning()).isTrue();
            original.stop(); // what a SocketException in the accept loop leaves behind: running=false, socket closed
            assertThat(original.isRunning()).isFalse();
            assertThat(server.needsRepair()).isTrue();

            assertThat(server.repair()).isTrue();
            ListenerRuntime replacement = server.listener("tcp-main");
            assertThat(replacement).isNotSameAs(original);
            assertThat(replacement.isRunning()).isTrue();
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), replacement.boundPort())) {
                assertThat(s.isConnected()).isTrue();
            }
        }
    }

    @Test
    void controlPlanePollLoopRepairsAFailedBind() throws Exception {
        try (ServerSocket blocker = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = blocker.getLocalPort();
            ProxyConfigDocument cfg = configOn(port);
            try (FakeControlPlane cp = new FakeControlPlane("t", cfg)) {
                ProxySettings settings = ProxySettings.defaults().withListenAddress("127.0.0.1").withAdminPort(0)
                        .withControlPlane(cp.url(), "t", "proxy-repair").withPolling(1, 60);
                try (ProxyApp app = ProxyApp.startEmbedded(settings, cfg, null, true)) {
                    assertThat(app.server().listenerErrors()).containsKey("tcp-main");
                    String admin = "http://127.0.0.1:" + app.admin().port();
                    assertThat(get(admin + "/health")).contains("\"status\" : \"DEGRADED\"").contains("\"listenerErrors\"");

                    blocker.close();
                    Await.until(6000, () -> app.server().listenerErrors().isEmpty(), "listener re-bound by the poll loop");
                    assertThat(app.server().listener("tcp-main").boundPort()).isEqualTo(port);
                    assertThat(get(admin + "/health")).contains("\"status\" : \"UP\"");
                }
            }
        }
    }
}
