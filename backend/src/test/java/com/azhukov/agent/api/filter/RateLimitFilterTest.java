package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private static final String IP_ONE = "203.0.113.10";
    private static final String IP_TWO = "203.0.113.20";

    private RateLimitFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new RateLimitFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    void requestUnderLimitPassesThrough() throws ServletException, IOException {
        when(request.getRemoteAddr()).thenReturn(IP_ONE);
        when(request.getRequestURI()).thenReturn("/api/agent");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void requestOverLimitReturns429() throws ServletException, IOException {
        when(request.getRemoteAddr()).thenReturn(IP_ONE);
        when(request.getRequestURI()).thenReturn("/api/agent");

        int capacity = readCapacity(filter);
        for (int i = 0; i <= capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(capacity)).doFilter(request, response);
        verify(response, times(1)).setStatus(429);
        verify(response, times(1)).setContentType("application/json");
        verify(response, times(1)).setCharacterEncoding("UTF-8");
        verify(response, times(1)).setHeader("Retry-After", "60");
        assertThat(responseWriter.toString()).contains("Rate limit exceeded");
        assertThat(responseWriter.toString()).contains("\"type\":\"rate_limit_error\"");
        assertThat(responseWriter.toString()).contains("\"code\":\"rate_limit_exceeded\"");
    }

    @Test
    void differentIpBucketsAreIndependent() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/agent");

        int capacity = readCapacity(filter);

        // Exhaust the bucket for IP_ONE.
        when(request.getRemoteAddr()).thenReturn(IP_ONE);
        for (int i = 0; i < capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        // Next request from IP_ONE is rejected.
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setStatus(429);

        // All requests from IP_TWO still pass through.
        when(request.getRemoteAddr()).thenReturn(IP_TWO);
        for (int i = 0; i < capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(capacity + capacity)).doFilter(request, response);
    }

    @Test
    void healthAndReadyPathsSkipRateLimit() throws ServletException, IOException {
        when(request.getRemoteAddr()).thenReturn(IP_ONE);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        int capacity = readCapacity(filter);
        for (int i = 0; i <= capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(capacity + 1)).doFilter(request, response);
        verify(response, never()).setStatus(429);

        // Reset mock invocation counters for the ready assertion.
        org.mockito.Mockito.reset(response, chain);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        when(request.getRemoteAddr()).thenReturn(IP_TWO);
        when(request.getRequestURI()).thenReturn("/ready");

        for (int i = 0; i <= capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(capacity + 1)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    private static int readCapacity(RateLimitFilter filter) {
        try {
            Field capacityField = RateLimitFilter.class.getDeclaredField("capacity");
            capacityField.setAccessible(true);
            return (int) capacityField.get(filter);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
