package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void registerCancellationCallback_firesOnCancel() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);
        token.registerCancellationCallback(sessionId, () -> fired.set(true));
        token.cancel(sessionId);
        assertThat(fired.get()).isTrue();
        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void unregister_removesCallback() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);
        token.registerCancellationCallback(sessionId, () -> fired.set(true));
        token.unregister(sessionId);
        token.cancel(sessionId);
        assertThat(fired.get()).isFalse();
    }

    @Test
    void registerNullCallback_isNoOp() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.registerCancellationCallback(sessionId, null);
        token.registerCancellationCallback(null, () -> {});
        // Should not throw or cause issues
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void callbackException_doesNotPreventCancellation() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.registerCancellationCallback(sessionId, () -> { throw new RuntimeException("boom"); });
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
    }
}