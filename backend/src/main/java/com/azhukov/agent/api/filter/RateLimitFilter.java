package com.azhukov.agent.api.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

    // Bounded LRU cache with max size to prevent unbounded growth.
    // Synchronized LinkedHashMap with access-order eviction — oldest entries
    // are evicted when the map exceeds MAX_BUCKETS.
    private static final int MAX_BUCKETS = 10_000;

    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_BUCKETS;
        }
    };
    private final int capacity = 200;
    private final Duration period = Duration.ofMinutes(1);
    private final boolean skipHealthChecks = true;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (skipHealthChecks && isHealthOrReadyPath(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String key = req.getRemoteAddr();
        Bucket bucket = getOrCreateBucket(key);
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            res.setStatus(429);
            res.getWriter().write("Rate limit exceeded");
        }
    }

    /**
     * Get or create a rate-limit bucket for the given key.
     * Uses synchronized access to the underlying LinkedHashMap to ensure thread safety.
     * The LinkedHashMap's LRU eviction caps the map at MAX_BUCKETS entries, preventing
     * unbounded memory growth from unique remote IPs.
     */
    private Bucket getOrCreateBucket(String key) {
        synchronized (buckets) {
            return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, period)))
                .build());
        }
    }

    private static boolean isHealthOrReadyPath(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals("/actuator/health") || uri.equals("/health") || uri.equals("/ready") || uri.equals("/alive");
    }
}