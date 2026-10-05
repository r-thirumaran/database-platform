package org.dbplatform.proxy.identity;

/** The application a connection was attributed to. {@code applicationId} may be null for an unregistered alias. */
public record ResolvedIdentity(String applicationId, String application, String teamId, IdentitySource source) {
    public static final String UNKNOWN = "unknown";

    public static ResolvedIdentity unknown() {
        return new ResolvedIdentity(null, UNKNOWN, null, IdentitySource.NONE);
    }

    public boolean isKnown() {
        return source != IdentitySource.NONE;
    }
}
