package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE emitter timeout must not cancel the turn's interrupt token.
 * Root cause (2026-08-27 21:27:54): provider cooldown retried for >600s,
 * SseEmitter(600_000L) fired onTimeout -> interruptToken.cancel() ->
 * the in-flight turn was poisoned as "[Turn ended: interrupted by user]"
 * (the user never interrupted), and the next user message re-ran the whole
 * tool plan from scratch. Parity: Hermes keeps the turn alive across
 * transport hiccups; only an explicit user action cancels.
 */
class SseTimeoutNoCancelTest {

    @Test
    void onTimeoutHandlerDoesNotReferenceInterruptTokenCancel() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/azhukov/agent/service/AgentStreamingService.java"));
        int onTimeout = src.indexOf("emitter.onTimeout(() -> {");
        int onError = src.indexOf("emitter.onError(");
        assertThat(onTimeout).isGreaterThan(0);
        assertThat(onError).isGreaterThan(onTimeout);
        String block = src.substring(onTimeout, onError);
        assertThat(block).doesNotContain("interruptToken.cancel");
        assertThat(block).contains("markDisconnected");
    }
}
