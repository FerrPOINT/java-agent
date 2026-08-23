package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streaming usage propagation (Hermes empty_response_guard parity): the
 * usage-carrying onComplete(finishReason, outputTokens) must reach the
 * handler so the deterministic-empty guard sees zero-output attempts.
 */
class StreamingUsagePropagationTest {

    @Test
    void usageCarryingCompleteReachesHandler() {
        AtomicReference<String> fr = new AtomicReference<>();
        AtomicReference<Long> tokens = new AtomicReference<>();
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
            @Override public void onComplete(String finishReason, Long outputTokens) {
                fr.set(finishReason);
                tokens.set(outputTokens);
            }
        };
        handler.onComplete("STOP", 0L);
        assertThat(fr.get()).isEqualTo("STOP");
        assertThat(tokens.get()).isZero();
    }

    @Test
    void defaultOverloadDropsUsageGracefully() {
        AtomicReference<String> fr = new AtomicReference<>();
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
            @Override public void onComplete(String finishReason) {
                fr.set(finishReason);
            }
        };
        // old-style handler: usage dropped, finish reason still delivered
        handler.onComplete("LENGTH", 42L);
        assertThat(fr.get()).isEqualTo("LENGTH");
    }

    @Test
    void nullUsageIsDeliveredAsNull() {
        AtomicReference<Long> tokens = new AtomicReference<>(999L);
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
            @Override public void onComplete(String finishReason, Long outputTokens) {
                tokens.set(outputTokens);
            }
        };
        handler.onComplete("STOP", null);
        assertThat(tokens.get()).isNull();
    }

    @Test
    void onToolCallsDefaultIsNoop() {
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
        };
        handler.onToolCalls(List.of(new ToolCall("id", "name", "{}")));
        // no exception = pass
    }
}
