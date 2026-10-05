package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dbplatform.common.telemetry.Engine;

import java.util.List;

/** Response of {@code GET /api/v1/internal/proxy/config?proxyId=…}: the proxy's complete routing configuration. */
public record ProxyConfig(
        @JsonProperty("configVersion") long configVersion,
        @JsonProperty("listeners") List<Listener> listeners,
        @JsonProperty("applications") List<ProxyApplication> applications,
        @JsonProperty("quotas") List<Quota> quotas,
        @JsonProperty("datasourceQuotas") List<DatasourceQuota> datasourceQuotas) {

    public ProxyConfig {
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
        applications = applications == null ? List.of() : List.copyOf(applications);
        quotas = quotas == null ? List.of() : List.copyOf(quotas);
        datasourceQuotas = datasourceQuotas == null ? List.of() : List.copyOf(datasourceQuotas);
    }

    /** One listening port of the proxy with its routes. */
    public record Listener(
            @JsonProperty("name") String name,
            @JsonProperty("engine") Engine engine,
            @JsonProperty("port") int port,
            @JsonProperty("routes") List<Route> routes,
            @JsonProperty("defaultRoute") Route defaultRoute) {

        public Listener {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    /**
     * Routing rule. {@code match} is compared with the logical service name requested by the client
     * (Oracle SERVICE_NAME/SID, PostgreSQL database); {@code <match>.<application-alias>} is also
     * accepted and yields the application identity. {@code match} is {@code null} for a default route.
     */
    public record Route(
            @JsonProperty("match") String match,
            @JsonProperty("datasourceId") String datasourceId,
            @JsonProperty("databaseId") String databaseId,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("serviceName") String serviceName,
            @JsonProperty("rewriteServiceName") boolean rewriteServiceName) {
    }

    /** Application the proxy may attribute connections to. */
    public record ProxyApplication(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("teamId") String teamId,
            @JsonProperty("identityRules") IdentityRules identityRules) {

        public ProxyApplication {
            identityRules = identityRules == null ? IdentityRules.none() : identityRules;
        }
    }

    /**
     * How a connection is attributed to an application, matched in this order: service alias
     * ({@code <match>.<alias>}), program name (Oracle CID PROGRAM / PG application_name), application
     * name, client machine (Oracle CID HOST), client CIDR. Entries are case-insensitive glob patterns
     * ({@code *} and {@code ?}).
     */
    public record IdentityRules(
            @JsonProperty("serviceAliases") List<String> serviceAliases,
            @JsonProperty("programs") List<String> programs,
            @JsonProperty("applicationNames") List<String> applicationNames,
            @JsonProperty("machines") List<String> machines,
            @JsonProperty("cidrs") List<String> cidrs) {

        public IdentityRules {
            serviceAliases = serviceAliases == null ? List.of() : List.copyOf(serviceAliases);
            programs = programs == null ? List.of() : List.copyOf(programs);
            applicationNames = applicationNames == null ? List.of() : List.copyOf(applicationNames);
            machines = machines == null ? List.of() : List.copyOf(machines);
            cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
        }

        public static IdentityRules none() {
            return new IdentityRules(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return serviceAliases.isEmpty() && programs.isEmpty() && applicationNames.isEmpty()
                    && machines.isEmpty() && cidrs.isEmpty();
        }
    }

    /** Per application/datasource connection quota enforced by the proxy. */
    public record Quota(
            @JsonProperty("applicationId") String applicationId,
            @JsonProperty("datasourceId") String datasourceId,
            @JsonProperty("maxProxyConnections") int maxProxyConnections) {
    }

    /** Per datasource connection quota (all applications together). */
    public record DatasourceQuota(
            @JsonProperty("datasourceId") String datasourceId,
            @JsonProperty("maxProxyConnections") int maxProxyConnections) {
    }
}
