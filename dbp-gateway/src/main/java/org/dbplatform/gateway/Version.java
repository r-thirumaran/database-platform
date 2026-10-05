package org.dbplatform.gateway;

/**
 * Version of the gateway as reported in HELLO_OK and heartbeats (from the jar manifest, falls back to the
 * development version).
 */
public final class Version {

    public static final String CURRENT = resolve();

    private Version() {
    }

    private static String resolve() {
        String v = Version.class.getPackage() != null ? Version.class.getPackage().getImplementationVersion() : null;
        return v != null && !v.isBlank() ? v : "0.1.0-SNAPSHOT";
    }
}
