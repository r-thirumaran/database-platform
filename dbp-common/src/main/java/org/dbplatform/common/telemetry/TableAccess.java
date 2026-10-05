package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A table touched by a statement, reported as written in the SQL (quoting characters removed,
 * case preserved). {@code schema} is {@code null} when the SQL was unqualified.
 */
public record TableAccess(
        @JsonProperty("schema") String schema,
        @JsonProperty("name") String name,
        @JsonProperty("access") AccessType access) {

    @JsonCreator
    public TableAccess {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("table name is required");
        }
        if (access == null) {
            access = AccessType.READ;
        }
    }

    public static TableAccess read(String schema, String name) {
        return new TableAccess(schema, name, AccessType.READ);
    }

    public static TableAccess write(String schema, String name) {
        return new TableAccess(schema, name, AccessType.WRITE);
    }

    /** {@code schema.name} or just {@code name} when unqualified. */
    public String qualifiedName() {
        return schema == null ? name : schema + "." + name;
    }

    public TableAccess withAccess(AccessType newAccess) {
        return access == newAccess ? this : new TableAccess(schema, name, newAccess);
    }
}
