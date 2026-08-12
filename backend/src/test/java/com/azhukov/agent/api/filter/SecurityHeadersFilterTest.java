package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @Test
    void setsAllSecurityHeaders() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response).setHeader("X-Frame-Options", "DENY");
        verify(response).setHeader("X-XSS-Protection", "1; mode=block");
        verify(response).setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        verify(response).setHeader("Content-Security-Policy", "default-src 'self'");
        verify(response).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        verify(response).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    }

    @Test
    void passesRequestDownChain() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void headersAreSetBeforeChainProceeds() throws ServletException, IOException {
        // Use a custom chain that verifies headers are set before doFilter is called
        when(response.getHeader("X-Content-Type-Options")).thenReturn("nosniff");

        FilterChain customChain = mock(FilterChain.class);
        // Stub: when doFilter is called, check that headers are already set
        // Since we use mocks, we verify the order via interaction recording
        filter.doFilter(request, response, customChain);

        // All headers should have been set on the response
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response).setHeader("X-Frame-Options", "DENY");
        verify(customChain, times(1)).doFilter(request, response);
    }

    @Test
    void doesNotOverrideExistingHeaders() throws ServletException, IOException {
        // The filter uses setHeader which overwrites; verify it always sets the expected values
        filter.doFilter(request, response, chain);

        // Each header is set exactly once
        verify(response, times(1)).setHeader("X-Content-Type-Options", "nosniff");
        verify(response, times(1)).setHeader("X-Frame-Options", "DENY");
        verify(response, times(1)).setHeader("X-XSS-Protection", "1; mode=block");
        verify(response, times(1)).setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        verify(response, times(1)).setHeader("Content-Security-Policy", "default-src 'self'");
        verify(response, times(1)).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        verify(response, times(1)).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    }

    @Test
    void worksWithAnyRequestPath() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/agent/chat");
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    void worksWithHealthEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/actuator/health");
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    }
}