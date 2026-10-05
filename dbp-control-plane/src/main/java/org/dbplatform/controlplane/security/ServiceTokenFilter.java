package org.dbplatform.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.dbplatform.controlplane.api.error.ApiError;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.Json;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** {@code /api/v1/internal/**} always requires {@code X-DBP-Service-Token} equal to {@code DBP_SERVICE_TOKEN}. */
@Component
@Order(1)
public class ServiceTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-DBP-Service-Token";
    private final DbpProperties props;

    public ServiceTokenFilter(DbpProperties props) { this.props = props; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || !constantTimeEquals(token, props.getServiceToken())) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(Json.write(new ApiError(401, "UNAUTHORIZED", "Missing or invalid " + HEADER, request.getRequestURI())));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
