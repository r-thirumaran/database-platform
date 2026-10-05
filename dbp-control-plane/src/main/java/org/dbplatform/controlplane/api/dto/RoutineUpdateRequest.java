package org.dbplatform.controlplane.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** PUT /routines/{id}: ownerTeamId (blank clears), description, tags. Absent fields are left unchanged. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutineUpdateRequest(String ownerTeamId, String description, List<String> tags) {
}
