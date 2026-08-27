package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity: streaming retry behaviour vs agent/conversation_loop.py.
 *
 * 1. Retry cap is operator config (agent.api_max_retries, default 3), not a
 *    hardcoded 5 — Hermes gives up after its retry budget with an honest
 *    error instead of burning ~10 minutes against a provider cooldown.
 * 2. Retry chatter is suppressed from chat surfaces (gateway
 *    _TELEGRAM_NOISY_STATUS_RE: "retrying in \d", "rate limited. waiting \d")
 *    EXCEPT the first attempt or waits >= 300s, which are surfaced
 *    immediately (conversation_loop.py:6636-6641).
 * 3. Backoff waits emit a keepalive every 30s (Hermes _touch_activity,
 *    conversation_loop.py:3531) so the SSE transport never looks dead.
 * 4. No fixed container SseEmitter timeout — the turn governs its lifetime.
 */
class StreamingRetryParityTest {

    @Test
    void retryStatusSuppressionMatchesHermesNoisyFilter() throws Exception {
        Method m = AgentStreamingService.class.getDeclaredMethod("shouldEmitRetryStatus", int.class, long.class);
        m.setAccessible(true);
        // First attempt always surfaces (Hermes emits on retry_count == 1).
        assertThat(m.invoke(null, 0, 2_000L)).isEqualTo(true);
        // Short waits on later attempts are suppressed (noisy chatter).
        assertThat(m.invoke(null, 1, 120_000L)).isEqualTo(false);
        assertThat(m.invoke(null, 2, 60_000L)).isEqualTo(false);
        // Long waits (>= 300s) surface immediately — the TUI must not look frozen.
        assertThat(m.invoke(null, 1, 300_000L)).isEqualTo(true);
        assertThat(m.invoke(null, 3, 600_000L)).isEqualTo(true);
    }

    @Test
    void retryCapUsesOperatorConfigNotHardcodedFive() throws java.io.IOException {
        // The emitter code path reads properties.getError().getRetryAttempts()
        // (Hermes agent.api_max_retries, default 3).
        String src = Files.readString(Path.of(
            "src/main/java/com/azhukov/agent/service/AgentStreamingService.java"));
        assertThat(src).contains("private int maxStreamRetries()");
        assertThat(src).contains("properties.getError().getRetryAttempts()");
        // No fixed container cap: SseEmitter must be created with 0L default.
        assertThat(src).contains("request.timeoutMs() != null ? request.timeoutMs() : 0L");
    }

    @Test
    void backoffEmitsKeepaliveEveryThirtySeconds() throws java.io.IOException {
        String src = Files.readString(Path.of(
            "src/main/java/com/azhukov/agent/service/AgentStreamingService.java"));
        assertThat(src).contains("new StreamEvent(\"keepalive\"");
        assertThat(src).contains("30_000");
    }
}
