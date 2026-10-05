package org.dbplatform.controlplane.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.dbplatform.controlplane.domain.Enums;

/** Create/update body of a credential; {@code secret} is only meaningful for INLINE and never echoed back. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CredentialRequest(
        @NotBlank @Size(max = 100) String name,
        String username,
        Enums.CredentialProvider provider,
        String ref,
        String secret,
        String description) {
}
