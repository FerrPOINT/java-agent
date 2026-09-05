package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.UserAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * API key authentication filter.
 * <p>
 * Reads {@code Authorization: Bearer ...}, {@code X-API-Key}, or {@code ?api_key=}
 * and validates it against {@code agent.security.api-key}. When the configured key is empty/null,
 * authentication is disabled (dev mode).
 * <p>
 * Simple health endpoints are always exempt; detailed health stays authenticated.
 */
@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AgentProperties agentProperties;
    private final UserAccessService userAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyAuthFilter(AgentProperties agentProperties, UserAccessService userAccessService) {
        this.agentProperties = agentProperties;
        this.userAccessService = userAccessService;
    }

    /** Test-only compatibility constructor without per-user auth. */
    public ApiKeyAuthFilter(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
        this.userAccessService = null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
        String configuredKey = agentProperties.getSecurity().getApiKey();

        // Auth disabled — dev mode: set a default authenticated principal
        if (configuredKey == null || configuredKey.isBlank()) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("dev"));
            UserContext.set(AgentProperties.DEFAULT_USER_ID, UserContext.ROLE_ADMIN);
            filterChain.doFilter(request, response);
            return;
        }

        String requestUri = request.getRequestURI();

        // Health endpoints are always exempt: set a default authenticated principal
        if (isHealthEndpoint(requestUri)) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("health"));
            UserContext.set("health", UserContext.ROLE_ADMIN);
            filterChain.doFilter(request, response);
            return;
        }

        if (isPlatformEventEndpoint(requestUri)) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("platform-event"));
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = extractApiKey(request);

        // Per-user API keys take precedence over the legacy global admin key.
        UserAccessService.AuthenticatedUser user = userAccessService != null && providedKey != null
            ? userAccessService.authenticate(providedKey) : null;
        if (user != null) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(providedKey));
            UserContext.set(user.userId(), user.role());
            filterChain.doFilter(request, response);
            return;
        }

        if (providedKey != null && constantTimeEquals(configuredKey, providedKey)) {
            // Set authentication in SecurityContext so Spring Security's
            // anyRequest().authenticated() check passes. Global key = admin.
            Authentication auth = new ApiKeyAuthentication(providedKey);
            SecurityContextHolder.getContext().setAuthentication(auth);
            UserContext.set(AgentProperties.DEFAULT_USER_ID, UserContext.ROLE_ADMIN);
            filterChain.doFilter(request, response);
            return;
        }

        // Auth failed — return 401 JSON error
        log.warn("API key authentication failed for request: {} {}", request.getMethod(), requestUri);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(errorJson(
            "Invalid gateway API key (API_SERVER_KEY)",
            "gateway_auth_error",
            "gateway_auth_failed"));
        } finally {
            // Always clear ThreadLocal to prevent leakage across virtual threads
            UserContext.clear();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        String bearerToken = extractBearerToken(request.getHeader("Authorization"));
        if (bearerToken != null && !bearerToken.isBlank()) {
            return bearerToken;
        }

        // Legacy header takes precedence over the query param.
        String headerKey = request.getHeader("X-API-Key");
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }

        String queryKey = request.getParameter("api_key");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey;
        }
        return null;
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }

    /**
     * Constant-time string comparison to prevent timing attacks on API key validation.
     * Compares across the configured key length and folds the supplied key length into the result.
     */
    private static boolean constantTimeEquals(String configuredKey, String providedKey) {
        byte[] expected = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = providedKey.getBytes(StandardCharsets.UTF_8);
        int result = expected.length ^ supplied.length;
        for (int i = 0; i < expected.length; i++) {
            byte suppliedByte = i < supplied.length ? supplied[i] : 0;
            result |= expected[i] ^ suppliedByte;
        }
        return result == 0;
    }

    private boolean isHealthEndpoint(String uri) {
        if (uri == null) {
            return false;
        }
        return ("/actuator/health".equals(uri) || uri.startsWith("/actuator/health/"))
            || "/health".equals(uri)
            || "/v1/health".equals(uri)
            || uri.matches("^/p/[^/]+/(?:health|v1/health)$");
    }

    private boolean isPlatformEventEndpoint(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.matches("^/api/platforms/[^/]+/events$")
            || uri.matches("^/p/[^/]+/api/platforms/[^/]+/events$");
    }

    private String errorJson(String message, String type, String code) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", Map.of(
                "message", message,
                "type", type,
                "code", code
            )));
        } catch (JsonProcessingException e) {
            return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type
                + "\",\"code\":\"" + code + "\"}}";
        }
    }

    /**
     * Simple authentication token representing an authenticated API key principal.
     * Used to satisfy Spring Security's {@code anyRequest().authenticated()} check.
     */
    private static class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final String apiKey;

        ApiKeyAuthentication(String apiKey) {
            super(List.of(new SimpleGrantedAuthority("ROLE_API")));
            this.apiKey = apiKey;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return apiKey;
        }

        @Override
        public Object getPrincipal() {
            return "api-user";
        }
    }
}
