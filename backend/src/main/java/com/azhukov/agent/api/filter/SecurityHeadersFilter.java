package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds security-related HTTP headers to all responses.
 * <p>
 * Headers set:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code X-Frame-Options: DENY}</li>
 *   <li>{@code X-XSS-Protection: 1; mode=block}</li>
 *   <li>{@code Strict-Transport-Security: max-age=31536000; includeSubDomains}</li>
 *   <li>{@code Content-Security-Policy: default-src 'self'}</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 *   <li>{@code Cache-Control: no-store, no-cache, must-revalidate}</li>
 * </ul>
 * Registered in {@link com.azhukov.agent.config.SecurityConfig} before the
 * {@link ApiKeyAuthFilter} so that headers are present even on 401 responses.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");

        filterChain.doFilter(request, response);
    }
}