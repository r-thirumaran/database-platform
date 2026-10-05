package org.dbplatform.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Minimal JSON client for the control plane REST API (public and {@code /internal} with the service token). */
public final class ControlPlaneApi {
    public static final ObjectMapper JSON = new ObjectMapper();

    public record Response(int status, JsonNode body, String text) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public Response expect(int... statuses) {
            for (int s : statuses) {
                if (s == status) {
                    return this;
                }
            }
            throw new AssertionError("unexpected HTTP status " + status + " (expected " + java.util.Arrays.toString(statuses) + "): " + text);
        }

        public JsonNode json() {
            if (!ok()) {
                throw new AssertionError("HTTP " + status + ": " + text);
            }
            return body;
        }
    }

    private final String baseUrl;
    private final String serviceToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ControlPlaneApi(String baseUrl, String serviceToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** {@code GET /api/v1 + path} (public). */
    public Response get(String path) {
        return send(request(path).GET(), false);
    }

    /** {@code GET /api/v1 + path} with the service token (for {@code /internal/...}). */
    public Response internalGet(String path) {
        return send(request(path).GET(), true);
    }

    public Response post(String path, Object body) {
        return send(request(path).POST(bodyOf(body)), false);
    }

    /** {@code POST /api/v1 + path} with the service token. */
    public Response internalPost(String path, Object body) {
        return send(request(path).POST(bodyOf(body)), true);
    }

    public Response put(String path, Object body) {
        return send(request(path).PUT(bodyOf(body)), false);
    }

    public Response delete(String path) {
        return send(request(path).DELETE(), false);
    }

    /** Any absolute URL (actuator, other components' admin endpoints). */
    public Response getAbsolute(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        return execute(req);
    }

    /** Any absolute URL with the service token (component admin endpoints that require it, e.g. the proxy's /connections). */
    public Response getAbsoluteWithToken(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("X-DBP-Service-Token", serviceToken).GET().build();
        return execute(req);
    }

    private HttpRequest.Builder request(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + p)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Content-Type", "application/json");
    }

    private Response send(HttpRequest.Builder b, boolean internal) {
        if (internal) {
            b.header("X-DBP-Service-Token", serviceToken);
        }
        return execute(b.build());
    }

    private Response execute(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = MissingNode.getInstance();
            String text = r.body() == null ? "" : r.body();
            if (!text.isBlank()) {
                try {
                    body = JSON.readTree(text);
                } catch (IOException ignored) {
                    // non-JSON body (e.g. prometheus text)
                }
            }
            return new Response(r.statusCode(), body, text);
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP " + req.method() + " " + req.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static HttpRequest.BodyPublisher bodyOf(Object body) {
        try {
            if (body == null) {
                return HttpRequest.BodyPublishers.ofString("{}");
            }
            if (body instanceof String s) {
                return HttpRequest.BodyPublishers.ofString(s);
            }
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- JSON conveniences -----------------------------------------------------------------------

    public static Stream<JsonNode> stream(JsonNode array) {
        if (array == null || !array.isArray()) {
            return Stream.empty();
        }
        return StreamSupport.stream(array.spliterator(), false);
    }

    /** Lists may be plain arrays or {@code {"items": [...]}} pages. */
    public static Stream<JsonNode> items(JsonNode listOrPage) {
        if (listOrPage != null && listOrPage.isObject() && listOrPage.has("items")) {
            return stream(listOrPage.get("items"));
        }
        return stream(listOrPage);
    }

    public static Optional<JsonNode> findByField(JsonNode list, String field, String value) {
        return items(list).filter(n -> value.equals(n.path(field).asText(null))).findFirst();
    }

    public static ObjectNode obj() {
        return JSON.createObjectNode();
    }

    public static String text(JsonNode n, String field) {
        return n == null ? null : n.path(field).asText(null);
    }
}
