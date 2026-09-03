package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiCorsFilter extends OncePerRequestFilter {

    private static final String ALLOW_METHODS = "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT";
    private static final String ALLOW_HEADERS = "Authorization, Content-Type, Idempotency-Key, X-API-Key, "
        + "X-Hermes-Session-Id, X-Hermes-Session-Key, X-Hermes-Session-Token";
    private static final String MAX_AGE_SECONDS = "600";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final AgentProperties agentProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            if (isOptions(request)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        List<String> allowedOrigins = allowedOrigins();
        boolean wildcard = allowedOrigins.contains("*");
        if (!isAllowedOrigin(origin, allowedOrigins, wildcard)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }

        applyCorsHeaders(request, response, origin, wildcard);
        if (isOptions(request)) {
            response.setStatus(HttpStatus.OK.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private List<String> allowedOrigins() {
        AgentProperties.ApiProperties api = agentProperties.getApi();
        if (api == null || api.getCorsOrigins() == null) {
            return List.of();
        }
        return api.getCorsOrigins().stream()
            .filter(origin -> origin != null && !origin.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private boolean isAllowedOrigin(String origin, List<String> allowedOrigins, boolean wildcard) {
        if (wildcard || allowedOrigins.contains(origin)) {
            return true;
        }
        return allowedOrigins.isEmpty() && isLoopbackOrigin(origin);
    }

    private boolean isLoopbackOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && LOOPBACK_HOSTS.contains(host.toLowerCase());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private void applyCorsHeaders(HttpServletRequest request,
                                  HttpServletResponse response,
                                  String origin,
                                  boolean wildcard) {
        response.setHeader("Access-Control-Allow-Origin", wildcard ? "*" : origin);
        response.setHeader("Access-Control-Allow-Methods", ALLOW_METHODS);
        String requestedHeaders = request.getHeader("Access-Control-Request-Headers");
        response.setHeader("Access-Control-Allow-Headers",
            requestedHeaders != null && !requestedHeaders.isBlank() ? requestedHeaders : ALLOW_HEADERS);
        response.setHeader("Access-Control-Max-Age", MAX_AGE_SECONDS);
        if (!wildcard) {
            response.setHeader("Vary", "Origin");
        }
    }

    private boolean isOptions(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }
}
