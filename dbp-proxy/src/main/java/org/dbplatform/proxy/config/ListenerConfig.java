package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A TCP listener: engine, port and the routes it knows. A missing port means the engine's conventional
 * port (1521 / 5432 / 1433); port 0 binds an ephemeral port (tests).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListenerConfig(
        String name,
        Engine engine,
        @JsonAlias({"bind", "address"}) String bindAddress,
        Integer port,
        Integer maxConnections,
        List<RouteConfig> routes,
        RouteConfig defaultRoute) {

    public static final int DEFAULT_MAX_CONNECTIONS = 5000;

    public ListenerConfig {
        if (engine == null) {
            throw new IllegalArgumentException("listener" + (name == null ? "" : " '" + name + "'") + " requires an engine");
        }
        if (port == null) {
            port = engine.defaultPort();
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("listener '" + name + "' has an invalid port " + port);
        }
        if (name == null || name.isBlank()) {
            name = engine.name().toLowerCase() + "-" + port;
        }
        if (maxConnections == null || maxConnections <= 0) {
            maxConnections = DEFAULT_MAX_CONNECTIONS;
        }
        routes = routes == null ? List.of() : List.copyOf(routes);
        for (RouteConfig r : routes) {
            if (r.match() == null || r.match().isBlank()) {
                throw new IllegalArgumentException("listener '" + name + "': every route needs a 'match'");
            }
        }
    }

    public int maxConnectionsOrDefault() {
        return maxConnections == null ? DEFAULT_MAX_CONNECTIONS : maxConnections;
    }

    /** True when the socket-level identity (engine, bind address, port) is unchanged, so the server socket can stay. */
    public boolean sameSocket(ListenerConfig other) {
        return other != null && engine == other.engine && java.util.Objects.equals(port, other.port)
                && java.util.Objects.equals(bindAddress, other.bindAddress);
    }
}
