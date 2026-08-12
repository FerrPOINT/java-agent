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

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        // Give the waiter time to start waiting
        Thread.sleep(100);

        // Approve from another thread
        queue.approve(sessionId, "approve", "ok");

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

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        Thread.sleep(100);

        queue.deny(sessionId, "no");

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

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 5000);
            decided.set(result);
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        Thread.sleep(100);

        queue.clear(sessionId);

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

        Thread waiter = new Thread(() -> {
            started.countDown();
            boolean result = queue.awaitDecision(sessionId, 30000);
            decided.set(result);
        });
        waiter.setDaemon(true);
        waiter.start();
        started.await();

        Thread.sleep(100);

        // Interrupt the waiting thread
        waiter.interrupt();

        waiter.join(2000);
        // Should return false due to interrupt
        assertThat(decided).isFalse();
        assertThat(waiter.isInterrupted()).isTrue();
    }
}