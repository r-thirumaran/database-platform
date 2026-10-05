package org.dbplatform.gateway;

import org.dbplatform.common.util.Env;
import org.dbplatform.common.util.Hostnames;
import org.dbplatform.protocol.ProtocolConstants;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Runtime settings of a gateway instance, read from {@code DBP_*} environment variables (system properties
 * are accepted as fallback). Every option is documented in the module README.
 *
 * @param gatewayId               identifier of this instance
 * @param port                    wire-protocol listen port (0 = ephemeral)
 * @param adminPort               admin HTTP port (0 = ephemeral, -1 = disabled)
 * @param bindAddress             bind address for both listeners
 * @param advertisedHost          host used in the logical {@code url} server property
 * @param idleTimeoutSeconds      socket read timeout for idle logical sessions
 * @param maxFrameBytes           maximum accepted/produced frame size
 * @param rowsFrameSoftBytes      soft byte limit of a ROWS frame (fewer rows than fetchSize are sent when exceeded)
 * @param maxSessions             global cap of logical sessions (0 = unlimited)
 * @param maxOpenCursorsPerSession cap of open cursors per session
 * @param shutdownGraceSeconds    how long to wait for in-flight statements on shutdown
 * @param controlPlaneUrl         control plane base URL, empty = static mode
 * @param serviceToken            shared service token for the control plane
 * @param staticConfigFile        static YAML config file (static mode)
 * @param authCacheSeconds        positive authentication cache TTL
 * @param configPollSeconds       config-version polling interval
 * @param poolStatsSeconds        PoolStats reporting interval
 * @param heartbeatSeconds        heartbeat interval
 * @param tlsKeystore             PKCS12/JKS keystore path for TLS (empty = plain TCP)
 * @param tlsKeystorePassword     keystore password
 */
public record GatewayConfig(
        String gatewayId,
        int port,
        int adminPort,
        String bindAddress,
        String advertisedHost,
        int idleTimeoutSeconds,
        int maxFrameBytes,
        int rowsFrameSoftBytes,
        int maxSessions,
        int maxOpenCursorsPerSession,
        int shutdownGraceSeconds,
        Optional<String> controlPlaneUrl,
        String serviceToken,
        Optional<Path> staticConfigFile,
        int authCacheSeconds,
        int configPollSeconds,
        int poolStatsSeconds,
        int heartbeatSeconds,
        Optional<Path> tlsKeystore,
        String tlsKeystorePassword) {

    public static final int DEFAULT_ADMIN_PORT = 7421;

    /** Reads the configuration from the environment. */
    public static GatewayConfig fromEnv() {
        return new GatewayConfig(
                Env.get("DBP_GATEWAY_ID", "gw-" + Hostnames.localShortName()),
                Env.getInt("DBP_GATEWAY_PORT", ProtocolConstants.DEFAULT_PORT),
                Env.getInt("DBP_GATEWAY_ADMIN_PORT", DEFAULT_ADMIN_PORT),
                Env.get("DBP_GATEWAY_BIND", "0.0.0.0"),
                Env.get("DBP_GATEWAY_ADVERTISED_HOST", Hostnames.localHostName()),
                Env.getInt("DBP_GATEWAY_IDLE_TIMEOUT_SECONDS", 1800),
                Env.getInt("DBP_GATEWAY_MAX_FRAME_BYTES", ProtocolConstants.DEFAULT_MAX_FRAME_BYTES),
                Env.getInt("DBP_GATEWAY_ROWS_FRAME_SOFT_BYTES", 4 * 1024 * 1024),
                Env.getInt("DBP_GATEWAY_MAX_SESSIONS", 0),
                Env.getInt("DBP_GATEWAY_MAX_OPEN_CURSORS", 256),
                Env.getInt("DBP_GATEWAY_SHUTDOWN_GRACE_SECONDS", 20),
                Env.lookup("DBP_CONTROL_PLANE_URL"),
                Env.get("DBP_SERVICE_TOKEN", "dev-service-token"),
                Env.lookup("DBP_GATEWAY_CONFIG").map(Path::of),
                Env.getInt("DBP_AUTH_CACHE_SECONDS", 60),
                Env.getInt("DBP_CONFIG_POLL_SECONDS", 5),
                Env.getInt("DBP_POOL_STATS_SECONDS", 15),
                Env.getInt("DBP_HEARTBEAT_SECONDS", 10),
                Env.lookup("DBP_GATEWAY_TLS_KEYSTORE").map(Path::of),
                Env.get("DBP_GATEWAY_TLS_KEYSTORE_PASSWORD", ""));
    }

    /** Defaults suitable for embedding (tests): ephemeral ports, static mode, no TLS. */
    public static GatewayConfig embedded(String gatewayId) {
        return new GatewayConfig(gatewayId, 0, -1, "127.0.0.1", "127.0.0.1", 1800,
                ProtocolConstants.DEFAULT_MAX_FRAME_BYTES, 4 * 1024 * 1024, 0, 256, 5,
                Optional.empty(), "dev-service-token", Optional.empty(), 60, 5, 15, 10, Optional.empty(), "");
    }

    public boolean controlPlaneMode() {
        return controlPlaneUrl.isPresent();
    }

    public GatewayConfig withGatewayId(String id) {
        return new GatewayConfig(id, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withTls(Path keystore, String password) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                Optional.ofNullable(keystore), password == null ? "" : password);
    }

    public GatewayConfig withPort(int p) {
        return new GatewayConfig(gatewayId, p, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withAdminPort(int p) {
        return new GatewayConfig(gatewayId, port, p, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withMaxSessions(int n) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, n, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withControlPlane(String url, String token) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, Optional.ofNullable(url),
                token, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withIntervals(int authCache, int configPoll, int poolStats, int heartbeat) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCache, configPoll, poolStats, heartbeat, tlsKeystore,
                tlsKeystorePassword);
    }

    public GatewayConfig withIdleTimeoutSeconds(int s) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, s, maxFrameBytes,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }

    public GatewayConfig withMaxFrameBytes(int n) {
        return new GatewayConfig(gatewayId, port, adminPort, bindAddress, advertisedHost, idleTimeoutSeconds, n,
                rowsFrameSoftBytes, maxSessions, maxOpenCursorsPerSession, shutdownGraceSeconds, controlPlaneUrl,
                serviceToken, staticConfigFile, authCacheSeconds, configPollSeconds, poolStatsSeconds, heartbeatSeconds,
                tlsKeystore, tlsKeystorePassword);
    }
}
