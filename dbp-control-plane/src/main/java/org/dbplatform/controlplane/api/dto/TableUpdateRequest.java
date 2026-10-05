package org.dbplatform.controlplane.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.dbplatform.controlplane.domain.TableMigration;

/** PUT /tables/{id}: only curated fields are writable; absent fields are left unchanged, blank ids clear a value. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableUpdateRequest(String ownerTeamId, Boolean ownerConfirmed, String producerApplicationId,
                                 String description, List<String> tags, String classification, TableMigration migration) {
}
