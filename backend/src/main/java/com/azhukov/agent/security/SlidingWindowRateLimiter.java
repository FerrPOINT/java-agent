package com.azhukov.agent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Per-tool/per-server sliding-window rate limiter.
 * <p>
 * Uses {@link ConcurrentLinkedDeque} for timestamps per key. The {@link #tryAcquire}
 * method prunes old timestamps and checks if the call is under the limit for the
 * given window. Thread-safe.
 */
@Slf4j
@Component
public class SlidingWindowRateLimiter {

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire a call slot for the given key.
     *
     * @param key           the rate-limit key (e.g. "serverName__toolName" or "serverName")
     * @param maxCalls      maximum calls allowed in the window
     * @param windowSeconds  the sliding window duration in seconds
     * @return {@code true} if the call is allowed (under the limit), {@code false} if rate-limited
     */
    public boolean tryAcquire(String key, int maxCalls, long windowSeconds) {
        if (key == null || key.isBlank()) {
            return true; // No key = no rate limiting
        }
        if (maxCalls <= 0 || windowSeconds <= 0) {
            return true; // No limit configured
        }

        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.SECONDS.toMillis(windowSeconds);

        ConcurrentLinkedDeque<Long> deque = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Prune timestamps older than the window
        pruneOldTimestamps(deque, windowStart);

        // Check current count
        if (deque.size() >= maxCalls) {
            log.warn("Rate limit exceeded for key '{}': {} calls in last {}s (max={})",
                key, deque.size(), windowSeconds, maxCalls);
            return false;
        }

        // Add current timestamp
        deque.addLast(now);
        return true;
    }

    /**
     * Gets the current number of calls in the window for the given key.
     *
     * @param key           the rate-limit key
     * @param windowSeconds  the sliding window duration in seconds
     * @return the count of calls within the window, or 0 if the key is unknown
     */
    public int getCurrentCount(String key, long windowSeconds) {
        if (key == null) return 0;
        ConcurrentLinkedDeque<Long> deque = windows.get(key);
        if (deque == null) return 0;
        long windowStart = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(windowSeconds);
        pruneOldTimestamps(deque, windowStart);
        return deque.size();
    }

    /**
     * Resets the rate limiter for a specific key.
     */
    public void reset(String key) {
        if (key != null) {
            windows.remove(key);
        }
    }

    /**
     * Clears all rate-limit state.
     */
    public void clear() {
        windows.clear();
    }

    /**
     * Removes timestamps that are older than the window start.
     * Since ConcurrentLinkedDeque is not indexed, we poll from the head
     * (oldest entries are at the head since timestamps are monotonically increasing).
     */
    private void pruneOldTimestamps(ConcurrentLinkedDeque<Long> deque, long windowStart) {
        while (true) {
            Long oldest = deque.peekFirst();
            if (oldest == null || oldest >= windowStart) {
                break;
            }
            deque.pollFirst();
        }
    }
}