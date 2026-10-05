package org.dbplatform.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.dbplatform.controlplane.config.DbpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** DBP_SECURITY_MODE=basic and the service-token filter, exercised without a Spring context. */
class SecurityFiltersTest {

    private static DbpProperties props(String mode) {
        DbpProperties p = new DbpProperties();
        p.getSecurity().setMode(mode);
        p.getSecurity().setAdminUser("ops");
        p.getSecurity().setAdminPassword("s3cret");
        p.setServiceToken("tok");
        return p;
    }

    private static MockHttpServletResponse run(jakarta.servlet.Filter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void basicModeProtectsPublicApiOnly() throws Exception {
        BasicAuthFilter f = new BasicAuthFilter(props("basic"));
        MockHttpServletResponse denied = run(f, new MockHttpServletRequest("GET", "/api/v1/teams"));
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getHeader("WWW-Authenticate")).contains("Basic");
        assertThat(denied.getContentAsString()).contains("\"error\":\"UNAUTHORIZED\"");
        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/api/v1/teams");
        ok.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("ops:s3cret".getBytes()));
        assertThat(run(f, ok).getStatus()).isEqualTo(200);
        MockHttpServletRequest wrong = new MockHttpServletRequest("GET", "/api/v1/teams");
        wrong.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("ops:nope".getBytes()));
        assertThat(run(f, wrong).getStatus()).isEqualTo(401);
        // UI, actuator, OpenAPI, CORS preflight and internal endpoints are not subject to basic auth
        assertThat(run(f, new MockHttpServletRequest("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(run(f, new MockHttpServletRequest("GET", "/actuator/health")).getStatus()).isEqualTo(200);
        assertThat(run(f, new MockHttpServletRequest("GET", "/v3/api-docs")).getStatus()).isEqualTo(200);
        assertThat(run(f, new MockHttpServletRequest("OPTIONS", "/api/v1/teams")).getStatus()).isEqualTo(200);
        assertThat(run(f, new MockHttpServletRequest("GET", "/api/v1/internal/config-version")).getStatus()).isEqualTo(200);
        // mode none: everything passes
        assertThat(run(new BasicAuthFilter(props("none")), new MockHttpServletRequest("GET", "/api/v1/teams")).getStatus()).isEqualTo(200);
    }

    @Test
    void serviceTokenFilterGuardsInternalEndpoints() throws Exception {
        ServiceTokenFilter f = new ServiceTokenFilter(props("none"));
        assertThat(run(f, new MockHttpServletRequest("GET", "/api/v1/internal/config-version")).getStatus()).isEqualTo(401);
        MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/api/v1/internal/config-version");
        bad.addHeader("X-DBP-Service-Token", "wrong");
        assertThat(run(f, bad).getStatus()).isEqualTo(401);
        MockHttpServletRequest good = new MockHttpServletRequest("POST", "/api/v1/internal/telemetry/queries");
        good.addHeader("X-DBP-Service-Token", "tok");
        assertThat(run(f, good).getStatus()).isEqualTo(200);
        assertThat(run(f, new MockHttpServletRequest("GET", "/api/v1/teams")).getStatus()).isEqualTo(200);
    }
}
