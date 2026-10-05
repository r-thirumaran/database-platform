package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The complete routing configuration of a proxy — identical in shape to
 * {@code GET /api/v1/internal/proxy/config} and to the static {@code proxy.yaml}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProxyConfigDocument(
        long configVersion,
        String proxyId,
        List<ListenerConfig> listeners,
        List<ApplicationConfig> applications,
        List<QuotaConfig> quotas,
        List<DatasourceQuotaConfig> datasourceQuotas) {

    public static final ProxyConfigDocument EMPTY = new ProxyConfigDocument(0, null, null, null, null, null);

    public ProxyConfigDocument {
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
        applications = applications == null ? List.of() : List.copyOf(applications);
        quotas = quotas == null ? List.of() : List.copyOf(quotas);
        datasourceQuotas = datasourceQuotas == null ? List.of() : List.copyOf(datasourceQuotas);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ListenerConfig l : listeners) {
            if (!names.add(l.name())) {
                throw new IllegalArgumentException("duplicate listener name '" + l.name() + "'");
            }
        }
    }

    public ProxyConfigDocument withVersion(long version) {
        return new ProxyConfigDocument(version, proxyId, listeners, applications, quotas, datasourceQuotas);
    }

    public ListenerConfig listener(String name) {
        for (ListenerConfig l : listeners) {
            if (l.name().equals(name)) {
                return l;
            }
        }
        return null;
    }
}
