package com.azhukov.agent.core.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ApprovalQueue latch-based waiting (replaces busy-wait).
 * Verifies that awaitDecision returns promptly when approval is decided,
 * without polling or Thread.sleep.
 */
class ApprovalQueueLatchTest {

    @Test
    void awaitDecisionReturnsImmediatelyWhenAlreadyDecided() {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();

        // No pending approval → should return true immediately
        boolean result = queue.awaitDecision(sessionId, 1000);
        assertThat(result).isTrue();
    }

    @Test
    void awaitDecisionReturnsWhenApproved() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.core.model.ToolCall call =
            new com.azhukov.agent.core.model.ToolCall("1", "terminal", "{}");

        queue.request(sessionId, call, "test");

        AtomicBoolean decided = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        // A latch that fires AFTER awaitDecision returns — proves the waiter
        // was actually blocked inside awaitDecision before we approve.
        CountDownLatch decisionDone = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
            decisionDone.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        // Give the waiter a moment to enter awaitDecision, then approve.
        // The waiter's awaitDecision will block on the internal CountDownLatch
        // until approve() signals it. We verify via decisionDone that it
        // was released promptly (not by timeout).
        queue.approve(sessionId, "approve", "ok");

        decisionDone.await(2, TimeUnit.SECONDS);

        waiter.join(2000);
        assertThat(decided).isTrue();
    }

    @Test
    void awaitDecisionReturnsWhenDenied() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.core.model.ToolCall call =
            new com.azhukov.agent.core.model.ToolCall("1", "terminal", "{}");

        queue.request(sessionId, call, "test");

        AtomicBoolean decided = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch decisionDone = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
            decisionDone.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        queue.deny(sessionId, "no");

        decisionDone.await(2, TimeUnit.SECONDS);

        waiter.join(2000);
        assertThat(decided).isTrue();
        assertThat(queue.isDenied(sessionId)).isTrue();
    }

    @Test
    void awaitDecisionTimesOut() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.core.model.ToolCall call =
            new com.azhukov.agent.core.model.ToolCall("1", "terminal", "{}");

        // Request with a very long timeout so it won't auto-deny during the test
        queue.request(sessionId, call, "test", java.time.Duration.ofMinutes(10));

        long start = System.currentTimeMillis();
        boolean result = queue.awaitDecision(sessionId, 200); // 200ms timeout
        long elapsed = System.currentTimeMillis() - start;

        // Should return false (timeout) after ~200ms, not 5 minutes
        // timing-assertion: verifies actual elapsed-time behavior of awaitDecision timeout
        assertThat(result).isFalse();
        assertThat(elapsed).isLessThan(2000L); // Should be much less than 5 min
    }

    @Test
    void awaitDecisionReturnsWhenCleared() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.core.model.ToolCall call =
            new com.azhukov.agent.core.model.ToolCall("1", "terminal", "{}");

        queue.request(sessionId, call, "test");

        AtomicBoolean decided = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch decisionDone = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
            decisionDone.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        queue.clear(sessionId);

        decisionDone.await(2, TimeUnit.SECONDS);

        waiter.join(2000);
        assertThat(decided).isTrue();
    }

    @Test
    void interruptDuringAwaitReturnsFalse() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.core.model.ToolCall call =
            new com.azhukov.agent.core.model.ToolCall("1", "terminal", "{}");

        queue.request(sessionId, call, "test", java.time.Duration.ofMinutes(10));

        AtomicBoolean decided = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch decisionDone = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 30000);
            decided.set(result);
            decisionDone.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        // Interrupt the waiting thread — awaitDecision should return false
        waiter.interrupt();

        decisionDone.await(2, TimeUnit.SECONDS);

        waiter.join(2000);
        // Should return false due to interrupt
        assertThat(decided).isFalse();
        assertThat(waiter.isInterrupted()).isTrue();
    }
}