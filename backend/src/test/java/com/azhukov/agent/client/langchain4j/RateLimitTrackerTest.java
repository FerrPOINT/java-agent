package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitTrackerTest {

    @Test
    void shouldBackoff_returnsFalse_whenUnknown() {
        RateLimitTracker tracker = new RateLimitTracker();
        assertThat(tracker.getRemaining()).isEqualTo(-1);
        assertThat(tracker.shouldBackoff()).isFalse();
    }

    @Test
    void shouldBackoff_returnsTrue_whenLowRemaining() {
        RateLimitTracker tracker = new RateLimitTracker();
        Instant future = Instant.now().plusSeconds(60);

        tracker.update(5, future);

        assertThat(tracker.shouldBackoff()).isTrue();
    }

    @Test
    void update_setsValues() {
        RateLimitTracker tracker = new RateLimitTracker();
        Instant someInstant = Instant.parse("2026-01-01T00:00:00Z");

        tracker.update(100, someInstant);

        assertThat(tracker.getRemaining()).isEqualTo(100);
        assertThat(tracker.getResetTime()).isEqualTo(someInstant);
    }
}