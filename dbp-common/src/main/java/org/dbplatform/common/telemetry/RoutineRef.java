package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A stored procedure / function reference as written in the SQL. For Oracle packaged routines the
 * name carries the package: {@code ORDER_PKG.PLACE_ORDER}; {@code schema} is {@code null} when the
 * SQL was unqualified.
 */
public record RoutineRef(
        @JsonProperty("schema") String schema,
        @JsonProperty("name") String name) {

    @JsonCreator
    public RoutineRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("routine name is required");
        }
    }

    public String qualifiedName() {
        return schema == null ? name : schema + "." + name;
    }
}
