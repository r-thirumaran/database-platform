package org.dbplatform.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.dbplatform.controlplane.api.error.ApiError;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.Json;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code DBP_SECURITY_MODE=basic}: public API ({@code /api/v1/**} except {@code /internal/**}) requires HTTP
 * Basic credentials {@code DBP_ADMIN_USER}/{@code DBP_ADMIN_PASSWORD}. Static UI, actuator health/prometheus
 * and the OpenAPI documents stay open. {@code none} (default) disables the filter.
 */
@Component
@Order(2)
public class BasicAuthFilter extends OncePerRequestFilter {
    private final DbpProperties props;

    public BasicAuthFilter(DbpProperties props) { this.props = props; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.getSecurity().isBasic()) return true;
        String uri = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return !uri.startsWith("/api/") || uri.startsWith("/api/v1/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
                int i = decoded.indexOf(':');
                if (i > 0) {
                    String user = decoded.substring(0, i), pass = decoded.substring(i + 1);
                    if (ServiceTokenFilter.constantTimeEquals(user, props.getSecurity().getAdminUser())
                            && ServiceTokenFilter.constantTimeEquals(pass, props.getSecurity().getAdminPassword())) {
                        chain.doFilter(request, response);
                        return;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // malformed base64 → 401 below
            }
        }
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Basic realm=\"dbp-control-plane\"");
        response.setContentType("application/json");
        response.getWriter().write(Json.write(new ApiError(401, "UNAUTHORIZED", "Authentication required", request.getRequestURI())));
    }
}
