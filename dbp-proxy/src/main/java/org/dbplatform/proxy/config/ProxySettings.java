package org.dbplatform.proxy.config;

import java.util.Locale;
import java.util.function.Function;

/**
 * Process-level settings, all from {@code DBP_*} environment variables (see README). Routing lives in
 * {@link ProxyConfigDocument}, which comes from the control plane or from {@code DBP_PROXY_CONFIG}.
 *
 * <p>{@code adminToken} is the value of {@code DBP_SERVICE_TOKEN} when it was explicitly set (environment
 * or system property) and {@code null} otherwise; when set, {@code GET /connections} and {@code GET /config}
 * require it as {@code X-DBP-Service-Token}. {@code serviceToken} always has a value (the control-plane
 * default) and is what the proxy sends to the control plane.
 */
public record ProxySettings(
        String proxyId,
        String listenAddress,
        String adminAddress,
        int adminPort,
        String adminToken,
        int idleTimeoutSeconds,
        int connectTimeoutMs,
        int handshakeTimeoutMs,
        int bufferBytes,
        String controlPlaneUrl,
        String serviceToken,
        int configPollSeconds,
        int heartbeatSeconds,
        String staticConfigPath,
        boolean strictAliases,
        int unknownAppMaxConnections) {

    public static final int DEFAULT_ADMIN_PORT = 7431;
    public static final String DEFAULT_SERVICE_TOKEN = "dev-service-token";

    public static ProxySettings fromEnv(Function<String, String> env) {
        String cpUrl = trimToNull(env.apply("DBP_CONTROL_PLANE_URL"));
        if (cpUrl != null && cpUrl.endsWith("/")) {
            cpUrl = cpUrl.substring(0, cpUrl.length() - 1);
        }
        String listen = orDefault(env.apply("DBP_PROXY_LISTEN_ADDRESS"), "0.0.0.0");
        String token = trimToNull(env.apply("DBP_SERVICE_TOKEN"));
        return new ProxySettings(
                orDefault(env.apply("DBP_PROXY_ID"), "proxy-1"),
                listen,
                orDefault(env.apply("DBP_PROXY_ADMIN_ADDRESS"), listen),
                intOr(env.apply("DBP_PROXY_ADMIN_PORT"), DEFAULT_ADMIN_PORT),
                token,
                intOr(env.apply("DBP_PROXY_IDLE_TIMEOUT_SECONDS"), 0),
                intOr(env.apply("DBP_PROXY_CONNECT_TIMEOUT_MS"), 5000),
                intOr(env.apply("DBP_PROXY_HANDSHAKE_TIMEOUT_MS"), 15000),
                intOr(env.apply("DBP_PROXY_BUFFER_BYTES"), 32 * 1024),
                cpUrl,
                token == null ? DEFAULT_SERVICE_TOKEN : token,
                intOr(env.apply("DBP_CONFIG_POLL_SECONDS"), 5),
                intOr(env.apply("DBP_HEARTBEAT_SECONDS"), 10),
                trimToNull(env.apply("DBP_PROXY_CONFIG")),
                boolOr(env.apply("DBP_PROXY_STRICT_ALIASES"), false),
                intOr(env.apply("DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS"), 0));
    }

    public static ProxySettings defaults() {
        return fromEnv(k -> null);
    }

    public boolean controlPlaneMode() {
        return controlPlaneUrl != null;
    }

    /** True when {@code /connections} and {@code /config} require {@code X-DBP-Service-Token}. */
    public boolean adminTokenRequired() {
        return adminToken != null;
    }

    public ProxySettings withAdminPort(int port) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, port, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    /** Bind address for the listeners and (unless {@link #withAdminAddress} is used) the admin API. */
    public ProxySettings withListenAddress(String address) {
        return new ProxySettings(proxyId, address, address, adminPort, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    public ProxySettings withAdminAddress(String address) {
        return new ProxySettings(proxyId, listenAddress, address, adminPort, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    /** Token required on the sensitive admin endpoints; {@code null} leaves them open. */
    public ProxySettings withAdminToken(String token) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, adminPort, trimToNull(token), idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    /** Control-plane mode with an explicit token: the token is also required on the sensitive admin endpoints. */
    public ProxySettings withControlPlane(String url, String token, String id) {
        return new ProxySettings(id, listenAddress, adminAddress, adminPort, trimToNull(token), idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, url, token, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    public ProxySettings withPolling(int pollSeconds, int heartbeatSecs) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, adminPort, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, pollSeconds, heartbeatSecs, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    public ProxySettings withTimeouts(int idleSeconds, int connectMs, int handshakeMs) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, adminPort, adminToken, idleSeconds, connectMs, handshakeMs,
                bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, unknownAppMaxConnections);
    }

    public ProxySettings withStrictAliases(boolean strict) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, adminPort, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strict, unknownAppMaxConnections);
    }

    public ProxySettings withUnknownAppMaxConnections(int max) {
        return new ProxySettings(proxyId, listenAddress, adminAddress, adminPort, adminToken, idleTimeoutSeconds, connectTimeoutMs,
                handshakeTimeoutMs, bufferBytes, controlPlaneUrl, serviceToken, configPollSeconds, heartbeatSeconds, staticConfigPath,
                strictAliases, max);
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

    private static boolean boolOr(String v, boolean def) {
        String t = trimToNull(v);
        if (t == null) {
            return def;
        }
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: '" + v + "'");
        };
    }
}
