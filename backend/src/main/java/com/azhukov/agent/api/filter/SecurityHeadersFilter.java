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
 *   <li>{@code X-XSS-Protection: 0}</li>
 *   <li>{@code Strict-Transport-Security: max-age=31536000; includeSubDomains}</li>
 *   <li>{@code Content-Security-Policy: default-src 'none'; frame-ancestors 'none'}</li>
 *   <li>{@code Permissions-Policy: camera=(), microphone=(), geolocation=()}</li>
 *   <li>{@code Referrer-Policy: no-referrer}</li>
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
        setDefaultHeader(response, "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        setDefaultHeader(response, "Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        setDefaultHeader(response, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        setDefaultHeader(response, "X-Content-Type-Options", "nosniff");
        setDefaultHeader(response, "X-Frame-Options", "DENY");
        setDefaultHeader(response, "X-XSS-Protection", "0");
        setDefaultHeader(response, "Referrer-Policy", "no-referrer");
        setDefaultHeader(response, "Cache-Control", "no-store, no-cache, must-revalidate");

        filterChain.doFilter(request, response);
    }

    private static void setDefaultHeader(HttpServletResponse response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }
}
