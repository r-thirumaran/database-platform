package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One backend a listener can forward to. {@code match} is the logical service name the client asks for
 * (Oracle SERVICE_NAME/SID, PostgreSQL database); {@code <match>.<alias>} is accepted too and the alias
 * resolves the application identity. {@code datasource}/{@code datasourceId} default to {@code match}
 * so static YAML files need not invent ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteConfig(
        String match,
        String datasource,
        String datasourceId,
        String databaseId,
        String host,
        int port,
        String serviceName,
        boolean rewriteServiceName) {

    public RouteConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("route" + (match == null ? "" : " '" + match + "'") + " requires a host");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("route" + (match == null ? "" : " '" + match + "'") + " has an invalid port " + port);
        }
        if (match != null) {
            if (datasource == null || datasource.isBlank()) {
                datasource = match;
            }
            if (datasourceId == null || datasourceId.isBlank()) {
                datasourceId = datasource;
            }
        }
        if (serviceName != null && serviceName.isBlank()) {
            serviceName = null;
        }
    }

    /** A default route (no {@code match}) built from host/port/service. */
    public static RouteConfig defaultRoute(String host, int port, String serviceName, boolean rewrite) {
        return new RouteConfig(null, null, null, null, host, port, serviceName, rewrite);
    }

    public String backend() {
        return host + ":" + port;
    }
}
