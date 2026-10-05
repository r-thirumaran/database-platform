package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An application known to the proxy; {@code id} defaults to {@code name} in static mode. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationConfig(String id, String name, String teamId, IdentityRules identityRules) {

    public ApplicationConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("application requires a name");
        }
        if (id == null || id.isBlank()) {
            id = name;
        }
        if (identityRules == null) {
            identityRules = IdentityRules.EMPTY;
        }
    }
}
