package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per (application, datasource) cap on live proxy connections. The control plane sends ids; static YAML
 * may use {@code application}/{@code datasource} names instead (ids default to names there).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuotaConfig(String applicationId, String application, String datasourceId, String datasource, int maxProxyConnections) {

    public QuotaConfig {
        if (applicationId == null || applicationId.isBlank()) {
            applicationId = application;
        }
        if (datasourceId == null || datasourceId.isBlank()) {
            datasourceId = datasource;
        }
        if (applicationId == null || datasourceId == null) {
            throw new IllegalArgumentException("quota requires applicationId/application and datasourceId/datasource");
        }
    }
}
