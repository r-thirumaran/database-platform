package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Coarse SQL statement class reported in {@link QueryEvent#operation()}. */
public enum SqlOperation {
    SELECT, INSERT, UPDATE, DELETE, MERGE, CALL, DDL, TXN,
    @JsonEnumDefaultValue OTHER;

    /** True for statements that modify table data (INSERT, UPDATE, DELETE, MERGE). */
    public boolean isDml() {
        return this == INSERT || this == UPDATE || this == DELETE || this == MERGE;
    }
}
