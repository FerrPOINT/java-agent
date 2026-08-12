package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String configuredKey = agentProperties.getSecurity().getApiKey();

        // Auth disabled — dev mode
        if (configuredKey == null || configuredKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestUri = request.getRequestURI();

        // Health endpoints are always exempt
        if (isHealthEndpoint(requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = extractApiKey(request);

        if (providedKey != null && providedKey.equals(configuredKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Auth failed — return 401 JSON error
        log.warn("API key authentication failed for request: {} {}", request.getMethod(), requestUri);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(errorJson("UNAUTHORIZED", "Invalid or missing API key"));
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
}