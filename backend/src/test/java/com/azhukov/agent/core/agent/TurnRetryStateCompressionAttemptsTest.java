package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TurnRetryState compression attempt counter (Feature 5).
 * Verifies the counter starts at 0, increments correctly, and is reflected in anyGuardConsumed().
 */
class TurnRetryStateCompressionAttemptsTest {

    @Test
    @DisplayName("Compression attempts counter starts at 0")
    void compressionAttemptsStartAtZero() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.getCompressionAttempts()).isZero();
        assertThat(state.isCompressionRestartAttempted()).isFalse();
    }

    @Test
    @DisplayName("incrementCompressionAttempts increments the counter")
    void incrementCompressionAttempts() {
        TurnRetryState state = new TurnRetryState();
        state.incrementCompressionAttempts();
        assertThat(state.getCompressionAttempts()).isEqualTo(1);
        state.incrementCompressionAttempts();
        assertThat(state.getCompressionAttempts()).isEqualTo(2);
        state.incrementCompressionAttempts();
        assertThat(state.getCompressionAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("setCompressionAttempts sets the counter directly")
    void setCompressionAttempts() {
        TurnRetryState state = new TurnRetryState();
        state.setCompressionAttempts(2);
        assertThat(state.getCompressionAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("anyGuardConsumed returns true when compressionAttempts > 0")
    void anyGuardConsumedWithCompressionAttempts() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.anyGuardConsumed()).isFalse();
        state.incrementCompressionAttempts();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    @DisplayName("anyGuardConsumed returns true when compressionRestartAttempted is true")
    void anyGuardConsumedWithBoolean() {
        TurnRetryState state = new TurnRetryState();
        state.setCompressionRestartAttempted(true);
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    @DisplayName("toString includes compressionAttempts")
    void toStringIncludesCompressionAttempts() {
        TurnRetryState state = new TurnRetryState();
        state.setCompressionAttempts(2);
        String str = state.toString();
        assertThat(str).contains("compressionAttempts=2");
    }
}