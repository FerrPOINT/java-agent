package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for InterruptToken — covers null session IDs,
 * reset on non-existent session, callback replaced by new registration.
 */
class InterruptTokenBranchCoverageTest {

    @Test
    void isCancelledNullSessionIdReturnsFalse() {
        InterruptToken token = new InterruptToken();
        assertThat(token.isCancelled(null)).isFalse();
    }

    @Test
    void cancelNullSessionIdIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.cancel(null);
        // Should not throw or set anything
        assertThat(token.isCancelled(null)).isFalse();
    }

    @Test
    void resetNullSessionIdIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.reset(null);
        // Should not throw
    }

    @Test
    void resetOnNonExistentSessionIsNoOp() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.reset(sessionId);
        // Should not throw — no flag set, nothing to clear
        assertThat(token.isCancelled(sessionId)).isFalse();
    }

    @Test
    void registerNullSessionIdDoesNotRegister() {
        InterruptToken token = new InterruptToken();
        AtomicBoolean fired = new AtomicBoolean(false);
        token.registerCancellationCallback(null, () -> fired.set(true));
        // Should not register
        token.cancel(UUID.randomUUID());
        assertThat(fired.get()).isFalse();
    }

    @Test
    void registerReplacementCallbackReplacesPrevious() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger(0);
        token.registerCancellationCallback(sessionId, () -> callCount.incrementAndGet());
        token.registerCancellationCallback(sessionId, () -> callCount.addAndGet(10));

        token.cancel(sessionId);
        // Only the second callback should fire
        assertThat(callCount.get()).isEqualTo(10);
    }

    @Test
    void unregisterNullSessionIdIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.unregister(null);
        // Should not throw
    }

    @Test
    void unregisterOnNonExistentSessionIsNoOp() {
        InterruptToken token = new InterruptToken();
        token.unregister(UUID.randomUUID());
        // Should not throw
    }

    @Test
    void cancelFiresCallbackThenIsCancelled() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        token.registerCancellationCallback(sessionId, () -> callbackFired.set(true));

        token.cancel(sessionId);
        assertThat(callbackFired.get()).isTrue();
        assertThat(token.isCancelled(sessionId)).isTrue();
    }

    @Test
    void cancelTwiceOnlyFiresCallbackOncePerCancel() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger(0);
        token.registerCancellationCallback(sessionId, () -> callCount.incrementAndGet());

        token.cancel(sessionId);
        token.cancel(sessionId);
        // Both cancels should fire the callback
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void resetAfterCancelClearsFlag() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
        token.reset(sessionId);
        assertThat(token.isCancelled(sessionId)).isFalse();
    }

    @Test
    void cancelAfterResetSetsFlagAgain() {
        InterruptToken token = new InterruptToken();
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        token.reset(sessionId);
        assertThat(token.isCancelled(sessionId)).isFalse();
        token.cancel(sessionId);
        assertThat(token.isCancelled(sessionId)).isTrue();
    }
}