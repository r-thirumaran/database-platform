package org.dbplatform.proxy.config;

import java.util.function.Function;

/**
 * Process-level settings, all from {@code DBP_*} environment variables (see README). Routing lives in
 * {@link ProxyConfigDocument}, which comes from the control plane or from {@code DBP_PROXY_CONFIG}.
 */
public record ProxySettings(
        String proxyId,
        String listenAddress,
        int adminPort,
        int idleTimeoutSeconds,
        int connectTimeoutMs,
        int handshakeTimeoutMs,
        int bufferBytes,
        String controlPlaneUrl,
        String serviceToken,
        int configPollSeconds,
        int heartbeatSeconds,
        String staticConfigPath) {

    public static final int DEFAULT_ADMIN_PORT = 7431;

    public static ProxySettings fromEnv(Function<String, String> env) {
        String cpUrl = trimToNull(env.apply("DBP_CONTROL_PLANE_URL"));
        if (cpUrl != null && cpUrl.endsWith("/")) {
            cpUrl = cpUrl.substring(0, cpUrl.length() - 1);
        }
        return new ProxySettings(
                orDefault(env.apply("DBP_PROXY_ID"), "proxy-1"),
                orDefault(env.apply("DBP_PROXY_LISTEN_ADDRESS"), "0.0.0.0"),
                intOr(env.apply("DBP_PROXY_ADMIN_PORT"), DEFAULT_ADMIN_PORT),
                intOr(env.apply("DBP_PROXY_IDLE_TIMEOUT_SECONDS"), 0),
                intOr(env.apply("DBP_PROXY_CONNECT_TIMEOUT_MS"), 5000),
                intOr(env.apply("DBP_PROXY_HANDSHAKE_TIMEOUT_MS"), 15000),
                intOr(env.apply("DBP_PROXY_BUFFER_BYTES"), 32 * 1024),
                cpUrl,
                orDefault(env.apply("DBP_SERVICE_TOKEN"), "dev-service-token"),
                intOr(env.apply("DBP_CONFIG_POLL_SECONDS"), 5),
                intOr(env.apply("DBP_HEARTBEAT_SECONDS"), 10),
                trimToNull(env.apply("DBP_PROXY_CONFIG")));
    }

    public static ProxySettings defaults() {
        return fromEnv(k -> null);
    }

    public boolean controlPlaneMode() {
        return controlPlaneUrl != null;
    }

    public ProxySettings withAdminPort(int port) {
        return new ProxySettings(proxyId, listenAddress, port, idleTimeoutSeconds, connectTimeoutMs, handshakeTimeoutMs,
                bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath);
    }

    public ProxySettings withListenAddress(String address) {
        return new ProxySettings(proxyId, address, adminPort, idleTimeoutSeconds, connectTimeoutMs, handshakeTimeoutMs,
                bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath);
    }

    public ProxySettings withControlPlane(String url, String token, String id) {
        return new ProxySettings(id, listenAddress, adminPort, idleTimeoutSeconds, connectTimeoutMs, handshakeTimeoutMs,
                bufferBytes, url, token, configPollSeconds, heartbeatSeconds, staticConfigPath);
    }

    public ProxySettings withPolling(int pollSeconds, int heartbeatSecs) {
        return new ProxySettings(proxyId, listenAddress, adminPort, idleTimeoutSeconds, connectTimeoutMs, handshakeTimeoutMs,
                bufferBytes, controlPlaneUrl, serviceToken, pollSeconds, heartbeatSecs, staticConfigPath);
    }

    public ProxySettings withTimeouts(int idleSeconds, int connectMs, int handshakeMs) {
        return new ProxySettings(proxyId, listenAddress, adminPort, idleSeconds, connectMs, handshakeMs,
                bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath);
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String orDefault(String v, String def) {
        String t = trimToNull(v);
        return t == null ? def : t;
    }

    private static int intOr(String v, int def) {
        String t = trimToNull(v);
        if (t == null) {
            return def;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an integer: '" + v + "'");
        }
    }
}
