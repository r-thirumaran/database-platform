package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.control.StaticResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Starts an in-process gateway in static mode on ephemeral ports.
 */
public final class GatewayFixture implements AutoCloseable {

    public final Gateway gateway;

    public GatewayFixture(GatewayConfig config, StaticConfig staticConfig) {
        this.gateway = new Gateway(config, new StaticResolver(staticConfig));
        try {
            gateway.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static GatewayFixture start(String gatewayId, List<StaticConfig.DatasourceConfig> datasources) {
        return new GatewayFixture(GatewayConfig.embedded(gatewayId).withAdminPort(0),
                new StaticConfig(gatewayId, datasources, List.of()));
    }

    public static GatewayFixture start(GatewayConfig config, StaticConfig staticConfig) {
        return new GatewayFixture(config, staticConfig);
    }

    public int port() {
        return gateway.port();
    }

    public int adminPort() {
        return gateway.adminPort();
    }

    public TestClient client(String datasource) {
        return TestClient.open(port(), datasource);
    }

    public String adminGet(String path) {
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + adminPort() + path)).GET().build();
            return http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        gateway.stop();
    }
}
