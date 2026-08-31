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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * API key authentication filter.
 * <p>
 * Reads the {@code X-API-Key} header (or {@code ?api_key=} query param) and validates it
 * against {@code agent.security.api-key}. When the configured key is empty/null,
 * authentication is disabled (dev mode).
 * <p>
 * Health endpoints ({@code /actuator/health/**}) are always exempt.
 */
@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AgentProperties agentProperties;
    private final UserAccessService userAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ApiKeyAuthFilter(AgentProperties agentProperties, UserAccessService userAccessService) {
        this.agentProperties = agentProperties;
        this.userAccessService = userAccessService;
    }

    /** Test-only compatibility constructor. */
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

            // Auth disabled — dev mode: set default user as admin
            if (configuredKey == null || configuredKey.isBlank()) {
                SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("dev"));
                UserContext.set(AgentProperties.DEFAULT_USER_ID, UserContext.ROLE_ADMIN);
                filterChain.doFilter(request, response);
                return;
            }

            String requestUri = request.getRequestURI();

            // Health endpoints are always exempt
            if (isHealthEndpoint(requestUri)) {
                SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("health"));
                UserContext.set("health", UserContext.ROLE_ADMIN);
                filterChain.doFilter(request, response);
                return;
            }

            String providedKey = extractApiKey(request);

            // Per-user API keys take precedence over the legacy global admin key.
            UserAccessService.AuthenticatedUser user = userAccessService != null
                ? userAccessService.authenticate(providedKey) : null;
            if (user != null) {
                SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(providedKey));
                UserContext.set(user.userId(), user.role());
                filterChain.doFilter(request, response);
                return;
            }

            if (providedKey != null && constantTimeEquals(configuredKey, providedKey)) {
                // Global API key = admin access
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
            response.getWriter().write(errorJson("UNAUTHORIZED", "Invalid or missing API key"));
        } finally {
            // Always clear ThreadLocal to prevent leakage across virtual threads
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        // Header takes precedence
        String headerKey = request.getHeader("X-API-Key");
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        // Fall back to query param
        String queryKey = request.getParameter("api_key");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey;
        }
        return null;
    }

    /**
     * Constant-time string comparison to prevent timing attacks on API key validation.
     * Always compares all characters regardless of early mismatch.
     */
    private static boolean constantTimeEquals(String configuredKey, String providedKey) {
        if (configuredKey.length() != providedKey.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < configuredKey.length(); i++) {
            result |= configuredKey.charAt(i) ^ providedKey.charAt(i);
        }
        return result == 0;
    }

    private boolean isHealthEndpoint(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/actuator/health");
    }

    private String errorJson(String type, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "type", type,
                "message", message
            ));
        } catch (JsonProcessingException e) {
            return "{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}";
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