package com.azhukov.agent.core.agent;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes /queue parity: the queued prompt is merged into EXACTLY ONE next turn
 * (consume-once). After applyCliState reports consumption, a caller clearing the
 * persisted value must observe the flag reset, and a second apply over the same
 * session must not re-merge the queued block.
 */
class CliStateApplierQueueConsumeTest {

    private ChatRequest full(String message, String queuedPrompt) {
        return new ChatRequest(null, message, null, null, null, null, null, null,
            null, null, null, null, null, null, queuedPrompt, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }

    private ChatRequest minimalRequest(String message) {
        return full(message, null);
    }

    @Test
    void queuedPromptIsConsumedExactlyOnce() {
        CliStateApplier applier = new CliStateApplier();
        SessionEntity session = new SessionEntity();
        session.setUserId("u1");
        session.setCliStateValue("queuedPrompt", "remember this once");

        ChatRequest first = applier.applyCliState(minimalRequest("turn one"), session);
        assertThat(first.message()).contains("[Queued context]").contains("remember this once");
        // The turn that merged it must report consumption so callers can clear the value.
        assertThat(applier.consumeQueuedPromptFlag()).isTrue();
        assertThat(applier.consumeQueuedPromptFlag()).isFalse(); // flag itself is one-shot

        // Simulate the caller's write-tx clear:
        session.removeCliStateValue("queuedPrompt");

        ChatRequest second = applier.applyCliState(minimalRequest("turn two"), session);
        assertThat(second.message()).doesNotContain("[Queued context]").contains("turn two");
        assertThat(applier.consumeQueuedPromptFlag()).isFalse();
    }

    @Test
    void requestCarriedQueuedPromptIsAlsoConsumed() {
        CliStateApplier applier = new CliStateApplier();
        SessionEntity session = new SessionEntity();
        session.setUserId("u1");

        ChatRequest withQueued = full("hello", "one-shot queue");
        ChatRequest applied = applier.applyCliState(withQueued, session);
        assertThat(applied.message()).contains("[Queued context]").contains("one-shot queue");
        assertThat(applier.consumeQueuedPromptFlag()).isTrue();
    }
}
