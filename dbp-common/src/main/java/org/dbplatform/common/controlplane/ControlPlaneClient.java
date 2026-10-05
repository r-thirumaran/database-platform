package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dbplatform.common.telemetry.Heartbeat;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.common.util.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous client for the control plane's internal endpoints (section 10 of
 * {@code docs/control-plane-api.md}). Every call sends {@code X-DBP-Service-Token}, applies the configured
 * timeout and throws {@link ControlPlaneException} (carrying the HTTP status) on any failure. Instances are
 * thread-safe and meant to be shared.
 */
public final class ControlPlaneClient {

    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneClient.class);

    public static final String SERVICE_TOKEN_HEADER = "X-DBP-Service-Token";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String INTERNAL = "/api/v1/internal";

    private final String baseUrl;
    private final String serviceToken;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = TelemetryJson.mapper();

    public ControlPlaneClient(String baseUrl, String serviceToken) {
        this(baseUrl, serviceToken, DEFAULT_TIMEOUT);
    }

    public ControlPlaneClient(String baseUrl, String serviceToken, Duration timeout) {
        this(baseUrl, serviceToken, timeout, null);
    }

    /** @param httpClient optional pre-built client (TLS etc.); {@code null} for a default one without proxy. */
    public ControlPlaneClient(String baseUrl, String serviceToken, Duration timeout, HttpClient httpClient) {
        this.baseUrl = stripSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.http = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Client configured from {@code DBP_CONTROL_PLANE_URL}, {@code DBP_SERVICE_TOKEN}, {@code DBP_CONTROL_PLANE_TIMEOUT}. */
    public static ControlPlaneClient fromEnv() {
        return new ControlPlaneClient(
                Env.get("DBP_CONTROL_PLANE_URL", "http://localhost:8080"),
                Env.get("DBP_SERVICE_TOKEN", "dev-service-token"),
                Env.getDuration("DBP_CONTROL_PLANE_TIMEOUT", DEFAULT_TIMEOUT));
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Duration timeout() {
        return timeout;
    }

    // ------------------------------------------------------------------ endpoints

    /**
     * {@code POST /internal/heartbeat}. Returns the control plane's current {@code configVersion}, empty
     * when the response carried none. Throws {@link ControlPlaneException} when the control plane is
     * unreachable or answers with an error status.
     */
    public Optional<Long> heartbeat(Heartbeat heartbeat) {
        ConfigVersion v = post(INTERNAL + "/heartbeat", heartbeat, ConfigVersion.class);
        return v == null ? Optional.empty() : Optional.ofNullable(v.configVersion());
    }

    /** {@code GET /internal/config-version}. */
    public long configVersion() {
        ConfigVersion v = get(INTERNAL + "/config-version", ConfigVersion.class);
        if (v == null || v.configVersion() == null) {
            throw new ControlPlaneException("control plane returned no configVersion", 200, "EMPTY",
                    INTERNAL + "/config-version");
        }
        return v.configVersion();
    }

    /** {@code POST /internal/auth/application}; throws with status 401 for an unknown or disabled key. */
    public ApplicationIdentity authenticateApplication(String apiKey) {
        return post(INTERNAL + "/auth/application", new AuthRequest(apiKey), ApplicationIdentity.class);
    }

    /**
     * {@code GET /internal/resolve/datasource/{name}?applicationId=…}; throws with status 403 when the
     * application has no enabled grant and 404 when the datasource is unknown.
     */
    public DatasourceResolution resolveDatasource(String datasourceName, String applicationId) {
        String path = INTERNAL + "/resolve/datasource/" + encode(datasourceName);
        if (applicationId != null && !applicationId.isBlank()) {
            path += "?applicationId=" + encode(applicationId);
        }
        return get(path, DatasourceResolution.class);
    }

    /** {@code GET /internal/credentials/{id}/material}. */
    public CredentialMaterial credentialMaterial(String credentialId) {
        return get(INTERNAL + "/credentials/" + encode(credentialId) + "/material", CredentialMaterial.class);
    }

    /** {@code GET /internal/proxy/config?proxyId=…}. */
    public ProxyConfig proxyConfig(String proxyId) {
        String path = INTERNAL + "/proxy/config";
        if (proxyId != null && !proxyId.isBlank()) {
            path += "?proxyId=" + encode(proxyId);
        }
        return get(path, ProxyConfig.class);
    }

    // ------------------------------------------------------------------ generic helpers (reusable by components)

    public <T> T get(String path, Class<T> responseType) {
        HttpRequest request = base(path).GET().build();
        return execute(request, path, responseType);
    }

    public <T> T post(String path, Object body, Class<T> responseType) {
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new ControlPlaneException("cannot serialise request body for " + path, path, e);
        }
        HttpRequest request = base(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return execute(request, path, responseType);
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(SERVICE_TOKEN_HEADER, serviceToken);
    }

    private <T> T execute(HttpRequest request, String path, Class<T> responseType) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ControlPlaneException("control plane request timed out after " + timeout.toMillis()
                    + " ms: " + request.method() + " " + path, path, e);
        } catch (IOException e) {
            throw new ControlPlaneException("control plane unreachable: " + request.method() + " " + path
                    + " (" + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")",
                    path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneException("interrupted while calling control plane: " + path, path, e);
        }
        int status = response.statusCode();
        byte[] body = response.body();
        if (status < 200 || status >= 300) {
            throw toException(status, path, body);
        }
        if (responseType == Void.class || body == null || body.length == 0) {
            return null;
        }
        try {
            return json.readValue(body, responseType);
        } catch (IOException e) {
            throw new ControlPlaneException("cannot parse control plane response for " + path + " as "
                    + responseType.getSimpleName() + ": " + e.getMessage(), status, "BAD_RESPONSE", path);
        }
    }

    private ControlPlaneException toException(int status, String path, byte[] body) {
        String error = null;
        String message = null;
        if (body != null && body.length > 0) {
            try {
                JsonNode node = json.readTree(body);
                if (node.isObject()) {
                    error = node.path("error").isTextual() ? node.path("error").asText() : null;
                    message = node.path("message").isTextual() ? node.path("message").asText() : null;
                }
            } catch (IOException ignored) {
                message = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8);
            }
        }
        if (error == null) {
            error = switch (status) {
                case 400 -> "BAD_REQUEST";
                case 401 -> "UNAUTHORIZED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 409 -> "CONFLICT";
                default -> status >= 500 ? "SERVER_ERROR" : "HTTP_" + status;
            };
        }
        String text = "control plane " + path + " -> HTTP " + status + " " + error
                + (message != null && !message.isBlank() ? ": " + message : "");
        LOG.debug(text);
        return new ControlPlaneException(text, status, error, path);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
