package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.security.CentralJwtVerifier;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.beans.factory.ObjectProvider;
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
 * Explicit Central Auth mode takes precedence over standalone gateway keys.
 * <p>
 * Reads {@code Authorization: Bearer ...}, {@code X-API-Key}, or {@code ?api_key=}
 * and validates it against {@code agent.security.api-key} only in standalone mode.
 * When the standalone configured key is empty/null,
 * authentication is disabled (dev mode).
 * <p>
 * Simple health endpoints are always exempt; detailed health stays authenticated.
 */
@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AgentProperties agentProperties;
    private final UserAccessService userAccessService;
    private CentralJwtVerifier centralJwtVerifier;
    private com.azhukov.agent.core.ports.IdentityReferenceRegistration identityReferences;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyAuthFilter(AgentProperties agentProperties, UserAccessService userAccessService) {
        this.agentProperties = agentProperties;
        this.userAccessService = userAccessService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureCentralVerifier(ObjectProvider<CentralJwtVerifier> provider) {
        centralJwtVerifier = provider.getIfAvailable();
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureIdentityReferences(ObjectProvider<com.azhukov.agent.core.ports.IdentityReferenceRegistration> provider) {
        identityReferences = provider.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return IdentityDeletionBoundaryFilter.privateOwner(request);
    }

    /** Test-only compatibility constructor without per-user auth. */
    public ApiKeyAuthFilter(AgentProperties agentProperties) {
        this(agentProperties, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
        if (centralJwtVerifier != null) {
            authenticateCentral(request, response, filterChain);
            return;
        }
        String configuredKey = agentProperties.getSecurity().getApiKey();

        // Auth disabled — dev mode: set a default authenticated principal
        if (configuredKey == null || configuredKey.isBlank()) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("dev"));
            setNativeUser(AgentProperties.DEFAULT_USER_ID, UserContext.ROLE_ADMIN, com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.DEVELOPMENT, true);
            filterChain.doFilter(request, response);
            return;
        }

        String requestUri = request.getRequestURI();

        // Prometheus scrape on the dedicated management port is exempt:
        // metrics carry endpoint names only, and the port (9969) sits on the
        // internal compose network.
        if ("/actuator/prometheus".equals(requestUri)) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("prometheus"));
            setNativeUser("prometheus", UserContext.ROLE_ADMIN, com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.DEVELOPMENT, true);
            filterChain.doFilter(request, response);
            return;
        }

        // Health endpoints are always exempt: set a default authenticated principal
        if (isHealthEndpoint(requestUri)) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("health"));
            setNativeUser("health", UserContext.ROLE_ADMIN, com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.DEVELOPMENT, true);
            filterChain.doFilter(request, response);
            return;
        }

        // Actuator info is explicitly public in SecurityConfig for deployment metadata.
        if ("/actuator/info".equals(requestUri)) {
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication("info"));
            setNativeUser("info", UserContext.ROLE_ADMIN, com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.DEVELOPMENT, true);
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
            setNativeUser(user.userId(), user.role(), com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.USER_API_KEY, UserContext.ROLE_ADMIN.equals(user.role()));
            filterChain.doFilter(request, response);
            return;
        }

        if (providedKey != null && constantTimeEquals(configuredKey, providedKey)) {
            // Set authentication in SecurityContext so Spring Security's
            // anyRequest().authenticated() check passes. Global key = admin.
            Authentication auth = new ApiKeyAuthentication(providedKey);
            SecurityContextHolder.getContext().setAuthentication(auth);
            setNativeUser(AgentProperties.DEFAULT_USER_ID, UserContext.ROLE_ADMIN, com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.GATEWAY_KEY, true);
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
            com.azhukov.agent.core.security.NativeIdentityOrigin.clear();
        }
    }

    private void authenticateCentral(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (isHealthEndpoint(uri) || "/actuator/prometheus".equals(uri)) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("health", null, List.of()));
            chain.doFilter(request, response);
            return;
        }
        final com.pdlc.auth.HumanApplicationAccess access;
        try {
            var verified=centralJwtVerifier.verifyIdentity(extractBearerToken(request.getHeader("Authorization")),request.getMethod());
            access=verified.access();
            if(identityReferences!=null)identityReferences.central(verified.issuer(),access.subject(),verified.window());
        } catch (CentralJwtVerifier.CentralAccessDeniedException denied) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(errorJson("Нет доступа к приложению", "authorization_error", "application_access_denied"));
            return;
        } catch (com.azhukov.agent.core.model.IdentityDeletionProtocol.Failure failure) {
            response.setStatus(failure.status());response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");response.setHeader("Cache-Control","no-store");
            response.getWriter().write(errorJson("Identity reference unavailable", "authentication_error", failure.code()));
            return;
        } catch (CentralJwtVerifier.CentralAuthUnavailableException unavailable) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(errorJson("Central authentication unavailable", "authentication_error",
                    "central_auth_unavailable"));
            return;
        } catch (JwtException rejected) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(errorJson("Central authentication required", "authentication_error",
                    "central_auth_failed"));
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(access.subject(), null,
                        List.of(new SimpleGrantedAuthority("admin".equals(access.role()) ? "ROLE_ADMIN" : "ROLE_USER"))));
        UserContext.setCentral(access);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private static void setNativeUser(String userId,String role,com.azhukov.agent.core.security.NativeIdentityOrigin.Kind kind,boolean mayNameScope) {
        UserContext.set(userId,role);
        com.azhukov.agent.core.security.NativeIdentityOrigin.bind(new com.azhukov.agent.core.security.NativeIdentityOrigin.Origin(kind,userId,mayNameScope));
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
