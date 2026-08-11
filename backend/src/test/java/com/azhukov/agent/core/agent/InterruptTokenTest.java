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

    // ── remove() tests ──

    @Test
    void remove_clearsAllState() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        token.registerCancellationCallback(sessionId, () -> {});
        assertThat(token.isCancelled(sessionId)).isTrue();

        token.remove(sessionId);

        assertThat(token.isCancelled(sessionId)).isFalse();
    }

    @Test
    void remove_nullSessionIdIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.remove(null);
        // Should not throw
    }

    @Test
    void remove_allowsReRegistrationAfterRemoval() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        token.remove(sessionId);

        assertThat(token.isCancelled(sessionId)).isFalse();

        // Re-cancel should work cleanly
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void remove_removesCallbackSoItDoesNotFire() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);
        token.registerCancellationCallback(sessionId, () -> fired.set(true));
        token.remove(sessionId);

        token.cancel(sessionId);
        // Callback was removed, so it should not fire
        assertThat(fired.get()).isFalse();
    }

    // ── cleanup() tests ──

    @Test
    void cleanup_removesOldEntries() throws InterruptedException {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();

        // Clean up with TTL of 0ms — should remove all entries
        Thread.sleep(2); // ensure timestamp is in the past
        token.cleanup(0);

        assertThat(token.isCancelled(sessionId)).isFalse();
    }

    @Test
    void cleanup_preservesRecentEntries() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();

        // Clean up with TTL of 1 hour — should preserve recent entries
        token.cleanup(3_600_000L);

        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void cleanup_defaultTtl_removesEntriesOlderThan1Hour() throws InterruptedException {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();

        // Default cleanup (1 hour TTL) — should preserve recent entries
        token.cleanup();

        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void cleanup_emptyMapsIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.cleanup();
        token.cleanup(0);
        // Should not throw
    }

    // ── isCancelledGlobally / ThreadLocal tests ──

    @Test
    void isCancelledGlobally_returnsFalseWhenNoSessionIdSet() {
        InterruptToken.setInstance(null);
        InterruptToken.clearCurrentSessionId();
        assertThat(InterruptToken.isCancelledGlobally()).isFalse();
    }

    @Test
    void isCancelledGlobally_returnsTrueWhenSessionCancelled() {
        InterruptToken token = new InterruptToken();
        InterruptToken.setInstance(token);
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);

        InterruptToken.setCurrentSessionId(sessionId);
        try {
            assertThat(InterruptToken.isCancelledGlobally()).isTrue();
        } finally {
            InterruptToken.clearCurrentSessionId();
            InterruptToken.setInstance(null);
        }
    }

    @Test
    void isCancelledGlobally_returnsFalseWhenSessionNotCancelled() {
        InterruptToken token = new InterruptToken();
        InterruptToken.setInstance(token);
        UUID sessionId = UUID.randomUUID();

        InterruptToken.setCurrentSessionId(sessionId);
        try {
            assertThat(InterruptToken.isCancelledGlobally()).isFalse();
        } finally {
            InterruptToken.clearCurrentSessionId();
            InterruptToken.setInstance(null);
        }
    }

    @Test
    void isCancelledGlobally_returnsFalseWhenNoInstanceRegistered() {
        InterruptToken.setInstance(null);
        UUID sessionId = UUID.randomUUID();
        InterruptToken.setCurrentSessionId(sessionId);
        try {
            assertThat(InterruptToken.isCancelledGlobally()).isFalse();
        } finally {
            InterruptToken.clearCurrentSessionId();
        }
    }
}