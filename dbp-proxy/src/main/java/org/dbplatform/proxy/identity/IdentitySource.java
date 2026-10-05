package org.dbplatform.proxy.identity;

/** Which rule produced an application identity (telemetry field {@code identitySource}). */
public enum IdentitySource {
    SERVICE_ALIAS, PROGRAM, APPLICATION_NAME, MACHINE, CIDR, NONE
}
