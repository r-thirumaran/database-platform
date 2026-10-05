package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code GET /api/v1/internal/credentials/{id}/material}. {@code toString()} masks the secret. */
public record CredentialMaterial(
        @JsonProperty("username") String username,
        @JsonProperty("secret") String secret,
        @JsonProperty("version") int version) {

    @Override
    public String toString() {
        return "CredentialMaterial[username=" + username + ", secret=***, version=" + version + "]";
    }
}
