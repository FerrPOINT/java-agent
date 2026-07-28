package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InterruptTokenTest {

    @Test
    void isCancelled_returnsFalse_whenNoToken() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        assertThat(token.isCancelled(sessionId)).isFalse();
    }

    @Test
    void cancel_setsFlag() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void reset_clearsFlag() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
        token.reset(sessionId);
        assertThat(token.isCancelled(sessionId)).isFalse();
    }
}