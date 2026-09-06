package com.azhukov.agent.core.agent;

import com.azhukov.agent.persistence.entity.SessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliStateApplierQueuedPromptTest {

    @Test
    void queuedPromptIsMergedAndFlagConsumed() {
        SessionEntity e = new SessionEntity();
        e.setCliStateValue("queuedPrompt", "follow-up");
        com.azhukov.agent.api.dto.ChatRequest req =
            com.azhukov.agent.api.dto.ChatRequest.simple(null, "main", null, null);
        // simulate the streaming service flow: sessionId present
        req = com.azhukov.agent.api.dto.ChatRequest.simple(
            java.util.UUID.randomUUID(), "main", null, null);

        CliStateApplier applier = new CliStateApplier();
        var applied = applier.applyCliState(req, e);
        assertThat(applied.message()).contains("follow-up");
        assertThat(applier.consumeQueuedPromptFlag()).isTrue();

        e.removeCliStateValue("queuedPrompt");
        assertThat(e.getCliStateValue("queuedPrompt")).isNull();
        var applied2 = applier.applyCliState(req, e);
        assertThat(applied2.message()).doesNotContain("follow-up");
        assertThat(applier.consumeQueuedPromptFlag()).isFalse();
    }
}
