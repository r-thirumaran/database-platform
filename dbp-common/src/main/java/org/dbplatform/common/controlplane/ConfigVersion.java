package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code GET /api/v1/internal/config-version} and {@code POST /api/v1/internal/heartbeat}. */
public record ConfigVersion(@JsonProperty("configVersion") Long configVersion) {
}
