package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dbplatform.common.telemetry.Engine;

import java.util.Map;

/**
 * Response of {@code GET /api/v1/internal/resolve/datasource/{name}?applicationId=…}: everything the
 * gateway needs to open a pool for an application on a logical datasource.
 */
public record DatasourceResolution(
        @JsonProperty("datasource") DatasourceInfo datasource,
        @JsonProperty("grant") GrantInfo grant,
        @JsonProperty("database") DatabaseInfo database,
        @JsonProperty("credential") CredentialRef credential,
        @JsonProperty("poolPolicy") PoolPolicy poolPolicy,
        @JsonProperty("configVersion") long configVersion) {

    /** Logical datasource. {@code state} is e.g. {@code ACTIVE}, {@code DRAINING}, {@code DISABLED}. */
    public record DatasourceInfo(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("state") String state) {
    }

    /** The application's access grant on the datasource. {@code poolMode} is {@code TRANSACTION} or {@code SESSION}. */
    public record GrantInfo(
            @JsonProperty("maxLogicalConnections") int maxLogicalConnections,
            @JsonProperty("readOnly") boolean readOnly,
            @JsonProperty("poolMode") String poolMode) {
    }

    /** Physical database the datasource currently points to. */
    public record DatabaseInfo(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("engine") Engine engine,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("serviceName") String serviceName,
            @JsonProperty("jdbcUrl") String jdbcUrl,
            @JsonProperty("jdbcProperties") Map<String, String> jdbcProperties) {

        public DatabaseInfo {
            jdbcProperties = jdbcProperties == null ? Map.of() : Map.copyOf(jdbcProperties);
        }
    }

    /** Credential reference (no secret): fetch the material with {@code GET /internal/credentials/{id}/material}. */
    public record CredentialRef(
            @JsonProperty("id") String id,
            @JsonProperty("username") String username,
            @JsonProperty("version") int version) {
    }
}
