package org.dbplatform.gateway.control;

import org.dbplatform.common.controlplane.CredentialMaterial;

import java.util.Optional;

/**
 * Authenticates applications and resolves logical datasources, either from static configuration or from the control
 * plane. Implementations cache aggressively; {@link #resolve} is called on every HELLO and again whenever a session
 * pins a physical connection (so credential rotations are picked up by new pins).
 */
public interface Resolver extends AutoCloseable {

    /** {@code "static"} or {@code "control-plane"}. */
    String mode();

    /**
     * Resolves a HELLO.
     *
     * @param datasource      logical datasource name (required)
     * @param apiKey          application api key (nullable)
     * @param applicationHint application name hint (nullable, static mode only)
     * @param user            logical user name (informational)
     */
    SessionResolution resolve(String datasource, String apiKey, String applicationHint, String user) throws AuthException;

    /** Fetches the credential material (username, secret, version) for a resolved datasource. */
    CredentialMaterial credentials(ResolvedDatasource datasource) throws AuthException;

    /** Whether the configuration source answered recently (always true in static mode). */
    boolean reachable();

    /** Last known control plane config version. */
    Optional<Long> configVersion();

    /** Feeds a config version learned elsewhere (heartbeat). */
    default void onConfigVersion(long version) {
    }

    /** Registers a listener invoked when the configuration version changes. */
    default void addConfigListener(Runnable listener) {
    }

    default void start() {
    }

    @Override
    default void close() {
    }
}
