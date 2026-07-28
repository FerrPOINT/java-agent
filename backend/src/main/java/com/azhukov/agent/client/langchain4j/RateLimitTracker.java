package com.azhukov.agent.client.langchain4j;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks rate-limit information from the model provider to enable proactive backoff.
 */
@Component
@Slf4j
public class RateLimitTracker {

    private static final int BACKOFF_THRESHOLD = 10;
    private static final int UNKNOWN = -1;

    private final AtomicInteger remaining = new AtomicInteger(UNKNOWN);
    private final AtomicReference<Instant> resetTime = new AtomicReference<>();

    /**
     * @return the remaining requests count, or -1 if unknown
     */
    public int getRemaining() {
        return remaining.get();
    }

    /**
     * @return the reset time, or null if unknown
     */
    public Instant getResetTime() {
        return resetTime.get();
    }

    /**
     * @return true if remaining is known (>= 0) and below the backoff threshold
     */
    public boolean shouldBackoff() {
        int r = remaining.get();
        return r >= 0 && r < BACKOFF_THRESHOLD;
    }

    /**
     * Update the rate-limit information.
     *
     * @param remaining  remaining requests
     * @param resetTime  when the limit resets
     */
    public void update(int remaining, Instant resetTime) {
        this.remaining.set(remaining);
        this.resetTime.set(resetTime);
        log.debug("Rate limit updated: remaining={}, resetTime={}", remaining, resetTime);
    }

    /**
     * Reset the tracker to the unknown state.
     */
    public void reset() {
        remaining.set(UNKNOWN);
        resetTime.set(null);
        log.debug("Rate limit tracker reset");
    }
}