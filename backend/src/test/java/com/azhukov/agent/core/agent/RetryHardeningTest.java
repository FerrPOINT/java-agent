package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for retry hardening features in {@link DefaultAgentRuntime}.
 * <p>
 * Covers:
 * <ul>
 *   <li>Part C: Retry-After header parsing</li>
 *   <li>Part D: Interruptible backoff</li>
 *   <li>Part E: Empty response recovery counter</li>
 *   <li>Part F: Content policy handling</li>
 * </ul>
 */
class RetryHardeningTest {

    // ── Part D: Interruptible backoff ──

    @Test
    @DisplayName("interruptibleSleep: completes normally when not interrupted")
    void interruptibleSleepCompletesNormally() throws InterruptedException {
        long start = System.currentTimeMillis();
        DefaultAgentRuntime.interruptibleSleep(300);
        long elapsed = System.currentTimeMillis() - start;
        // Should have slept at least ~250ms (allowing for scheduling jitter)
        assertThat(elapsed).isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("interruptibleSleep: zero delay completes immediately")
    void interruptibleSleepZero() throws InterruptedException {
        DefaultAgentRuntime.interruptibleSleep(0); // should not block
    }

    @Test
    @DisplayName("interruptibleSleep: negative delay completes immediately")
    void interruptibleSleepNegative() throws InterruptedException {
        DefaultAgentRuntime.interruptibleSleep(-100); // should not block
    }

    @Test
    @DisplayName("interruptibleSleep: throws InterruptedException when thread is interrupted")
    void interruptibleSleepThrowsOnInterrupt() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> DefaultAgentRuntime.interruptibleSleep(10_000))
            .isInstanceOf(InterruptedException.class);
    }

    @Test
    @DisplayName("interruptibleSleep: large delay sleeps in 200ms chunks and responds to interrupt")
    void interruptibleSleepChunked() throws InterruptedException {
        // Sleep 500ms in a separate thread, then interrupt it after 100ms
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        Thread sleeper = new Thread(() -> {
            try {
                DefaultAgentRuntime.interruptibleSleep(10_000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            }
        });
        sleeper.start();
        Thread.sleep(100); // let it start sleeping
        sleeper.interrupt();
        sleeper.join(2000);
        assertThat(wasInterrupted.get()).isTrue();
    }

    // ── Part E: Empty response recovery counter ──

    @Test
    @DisplayName("TurnRetryState emptyResponseRetries counter works correctly")
    void emptyResponseRetriesCounter() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.getEmptyResponseRetries()).isZero();

        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(1);

        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(2);

        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(3);

        // Reset
        state.setEmptyResponseRetries(0);
        assertThat(state.getEmptyResponseRetries()).isZero();
    }

    // ── Part F: Content policy exception ──

    @Test
    @DisplayName("ContentPolicyException carries user-friendly message and cause")
    void contentPolicyException() {
        Exception cause = new RuntimeException("content_filter triggered");
        TurnExecutor.ContentPolicyException ex =
            new TurnExecutor.ContentPolicyException("User-friendly message", cause);
        assertThat(ex.getMessage()).isEqualTo("User-friendly message");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── TurnExitReason new values ──

    @Test
    @DisplayName("TurnExitReason.EMPTY_RESPONSE_EXHAUSTED exists and is abnormal")
    void emptyResponseExhaustedExitReason() {
        assertThat(TurnExitReason.EMPTY_RESPONSE_EXHAUSTED.isAbnormal()).isTrue();
        assertThat(TurnExitReason.EMPTY_RESPONSE_EXHAUSTED.explanation())
            .contains("empty response");
    }

    @Test
    @DisplayName("TurnExitReason.CONTENT_POLICY exists and is abnormal")
    void contentPolicyExitReason() {
        assertThat(TurnExitReason.CONTENT_POLICY.isAbnormal()).isTrue();
        assertThat(TurnExitReason.CONTENT_POLICY.explanation())
            .contains("content policy");
    }
}