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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity = 60;
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
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
            .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, period)))
            .build());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            res.setStatus(429);
            res.getWriter().write("Rate limit exceeded");
        }
    }

    private static boolean isHealthOrReadyPath(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals("/actuator/health") || uri.equals("/health") || uri.equals("/ready") || uri.equals("/alive");
    }
}
