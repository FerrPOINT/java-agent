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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * M20: Test that the rate limit filter uses a bounded cache that
 * prevents unbounded memory growth from unique remote IPs.
 */
class RateLimitFilterBoundedCacheTest {

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
    void bucketsMapIsBoundedWithMaxSize() throws Exception {
        Field bucketsField = RateLimitFilter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        Map<String, ?> buckets = (Map<String, ?>) bucketsField.get(filter);

        // Generate many unique IPs — each creates a bucket
        when(request.getRequestURI()).thenReturn("/api/test");
        for (int i = 0; i < 100; i++) {
            when(request.getRemoteAddr()).thenReturn("10.0.0." + i);
            filter.doFilter(request, response, chain);
        }

        // The map should not exceed MAX_BUCKETS (10,000)
        assertThat(buckets.size()).isLessThanOrEqualTo(10_000);
        assertThat(buckets.size()).isEqualTo(100);
    }

    @Test
    void lruEvictionPreventsUnboundedGrowth() throws Exception {
        Field bucketsField = RateLimitFilter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        Field capacityField = RateLimitFilter.class.getDeclaredField("capacity");
        capacityField.setAccessible(true);
        int capacity = (int) capacityField.get(filter);

        Map<String, ?> buckets = (Map<String, ?>) bucketsField.get(filter);

        when(request.getRequestURI()).thenReturn("/api/test");

        // Add many unique IPs — the LRU eviction should cap the map size
        for (int i = 0; i < 15_000; i++) {
            when(request.getRemoteAddr()).thenReturn("192.168." + (i / 256) + "." + (i % 256));
            filter.doFilter(request, response, chain);
        }

        // The map should be capped at MAX_BUCKETS (10,000)
        assertThat(buckets.size()).isLessThanOrEqualTo(10_000);
    }

    @Test
    void healthPathsSkipRateLimit() throws ServletException, IOException {
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(request.getRequestURI()).thenReturn("/actuator/health");

        int capacity = readCapacity(filter);
        for (int i = 0; i <= capacity; i++) {
            filter.doFilter(request, response, chain);
        }

        // All requests should pass through — health is exempt
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