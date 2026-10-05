package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Per datasource cap on live proxy connections across all applications. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatasourceQuotaConfig(String datasourceId, String datasource, int maxProxyConnections) {

    public DatasourceQuotaConfig {
        if (datasourceId == null || datasourceId.isBlank()) {
            datasourceId = datasource;
        }
        if (datasourceId == null) {
            throw new IllegalArgumentException("datasource quota requires datasourceId/datasource");
        }
    }
}
