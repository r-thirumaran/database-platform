package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Lifecycle event of a proxied connection. */
public enum ConnectionEventType {
    OPEN, CLOSE, REFUSED,
    @JsonEnumDefaultValue BACKEND_FAILED
}
