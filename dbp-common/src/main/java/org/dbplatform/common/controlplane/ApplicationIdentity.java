package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response of {@code POST /api/v1/internal/auth/application}. */
public record ApplicationIdentity(
        @JsonProperty("applicationId") String applicationId,
        @JsonProperty("name") String name,
        @JsonProperty("teamId") String teamId,
        @JsonProperty("teamName") String teamName,
        @JsonProperty("tags") List<String> tags) {

    public ApplicationIdentity {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
