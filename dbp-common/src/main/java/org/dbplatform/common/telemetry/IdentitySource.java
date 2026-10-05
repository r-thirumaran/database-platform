package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** How the proxy identified the application behind a connection. */
public enum IdentitySource {
    SERVICE_ALIAS, PROGRAM, APPLICATION_NAME, MACHINE, CIDR,
    @JsonEnumDefaultValue NONE
}
