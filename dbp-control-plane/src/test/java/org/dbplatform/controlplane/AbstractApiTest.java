package org.dbplatform.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** One Spring context (H2 in-memory) shared by every API test class. Tests create uniquely named objects. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiTest {
    public static final String TOKEN = "test-service-token";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected static String uniq(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    protected JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        return perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expectedStatus);
    }

    protected JsonNode putJson(String path, Object body, int expectedStatus) throws Exception {
        return perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expectedStatus);
    }

    protected JsonNode getJson(String path, int expectedStatus) throws Exception {
        return perform(get(path), expectedStatus);
    }

    protected JsonNode deleteJson(String path, int expectedStatus) throws Exception {
        return perform(delete(path), expectedStatus);
    }

    protected JsonNode internalGet(String path, int expectedStatus) throws Exception {
        return perform(get(path).header("X-DBP-Service-Token", TOKEN), expectedStatus);
    }

    protected JsonNode internalPost(String path, Object body, int expectedStatus) throws Exception {
        String content = body instanceof String s ? s : json.writeValueAsString(body);
        return perform(post(path).header("X-DBP-Service-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(content), expectedStatus);
    }

    protected JsonNode perform(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString();
        if (r.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("expected " + expectedStatus + " but got " + r.getResponse().getStatus() + " for " + r.getRequest().getMethod() + " "
                    + r.getRequest().getRequestURI() + ": " + body);
        }
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    protected JsonNode team(String name) throws Exception {
        return postJson("/api/v1/teams", Map.of("name", name, "displayName", name, "contacts", java.util.List.of(name + "@example.org")), 201);
    }

    protected JsonNode application(String name, String teamId, String kind, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "teamId", teamId, "kind", kind));
        body.putAll(extra);
        return postJson("/api/v1/applications", body, 201);
    }

    protected JsonNode credential(String name, String provider, String ref, String secret) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "username", "APP_USER", "provider", provider));
        if (ref != null) body.put("ref", ref);
        if (secret != null) body.put("secret", secret);
        return postJson("/api/v1/credentials", body, 201);
    }

    protected JsonNode database(String name, String engine, String host, int port, String service, String credentialId, java.util.List<String> schemas) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "engine", engine, "host", host, "port", port, "serviceName", service, "maxPhysicalConnections", 60,
                "collector", Map.of("enabled", false, "schemas", schemas)));
        if (credentialId != null) body.put("credentialId", credentialId);
        return postJson("/api/v1/databases", body, 201);
    }

    protected JsonNode datasource(String name, String ownerTeamId, String currentDbId, String targetDbId, java.util.List<Map<String, Object>> rules) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "ownerTeamId", ownerTeamId, "state", targetDbId == null ? "ACTIVE" : "MIGRATING",
                "currentDatabaseId", currentDbId, "poolPolicy", Map.of("mode", "TRANSACTION", "maxConnections", 12, "minIdle", 1)));
        if (targetDbId != null) body.put("targetDatabaseId", targetDbId);
        if (rules != null) body.put("routingRules", rules);
        return postJson("/api/v1/datasources", body, 201);
    }

    protected JsonNode grant(String appId, String dsId, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("applicationId", appId, "datasourceId", dsId, "maxLogicalConnections", 20, "maxProxyConnections", 10));
        body.putAll(extra);
        return postJson("/api/v1/access-grants", body, 201);
    }
}
