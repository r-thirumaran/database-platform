package org.dbplatform.proxy.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * How observed connections are mapped to an application (mirrors {@code Application.identityRules}).
 * Both the API-document spellings ({@code programNames}, {@code machinePatterns}, {@code pgApplicationNames})
 * and the {@code dbp-common} spellings ({@code programs}, {@code machines}, {@code applicationNames}) are accepted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityRules(
        List<String> cidrs,
        @JsonAlias({"programs"}) List<String> programNames,
        @JsonAlias({"machines"}) List<String> machinePatterns,
        List<String> serviceAliases,
        @JsonAlias({"applicationNames"}) List<String> pgApplicationNames) {

    public static final IdentityRules EMPTY = new IdentityRules(null, null, null, null, null);

    public IdentityRules {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
        programNames = programNames == null ? List.of() : List.copyOf(programNames);
        machinePatterns = machinePatterns == null ? List.of() : List.copyOf(machinePatterns);
        serviceAliases = serviceAliases == null ? List.of() : List.copyOf(serviceAliases);
        pgApplicationNames = pgApplicationNames == null ? List.of() : List.copyOf(pgApplicationNames);
    }
}
