package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST /api/v1/internal/auth/application}. */
public record AuthRequest(@JsonProperty("apiKey") String apiKey) {

    @Override
    public String toString() {
        return "AuthRequest[apiKey=***]";
    }
}
