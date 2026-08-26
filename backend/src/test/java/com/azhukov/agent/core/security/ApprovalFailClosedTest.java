package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HERMES-SYNC (tools/approval.py:2984): approval timeouts must be FAIL-CLOSED.
 * 'timeout' means the prompt expired without a user response — the action must
 * still be blocked, reported as "no response" rather than an explicit denial.
 * The execution gate (TurnExecutor / DefaultAgentRuntime) uses isApproved()
 * after awaitDecision(); these tests pin the queue-side semantics it relies on.
 */
class ApprovalFailClosedTest {

    private static ToolCall call() {
        return new ToolCall("call_1", "terminal", "{\"command\": \"rm -rf /tmp/x\"}");
    }

    @Test
    void timedOutApprovalIsNotApproved() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        queue.request(session, call(), "destructive", Duration.ofMillis(50));

        // Wait past the expiry, then simulate the executor's gate sequence.
        // timing-assertion: verifies fail-closed after expiry duration
        Thread.sleep(120);
        // getPending() auto-denies on expiry (fail-closed at read time)
        ApprovalQueue.PendingApproval p = queue.getPending(session);
        assertNotNull(p);
        assertTrue(p.denied(), "expired approval must read as denied (auto-deny)");
        assertFalse(p.approved(), "expired approval must never read as approved");
        assertTrue(queue.isDenied(session));
        assertFalse(queue.isApproved(session), "timeout WITHOUT user response must NOT count as consent");
    }

    @Test
    void pendingApprovalIsNotApprovedWhileUndecided() {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        queue.request(session, call(), "destructive", Duration.ofMinutes(5));

        assertFalse(queue.isApproved(session), "undecided approval must not execute");
        assertTrue(queue.isPending(session));
    }

    @Test
    void awaitDecisionTimesOutAndIsApprovedStaysFalse() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        queue.request(session, call(), "destructive", Duration.ofMinutes(5));

        AtomicReference<Boolean> decided = new AtomicReference<>(null);
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            decided.set(queue.awaitDecision(session, 150));
            done.countDown();
        });
        waiter.start();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        // No user response within the wait → decided=false, and the executor's
        // isApproved() re-check must block the call (fail-closed).
        assertEquals(Boolean.FALSE, decided.get());
        assertFalse(queue.isApproved(session));
        assertTrue(queue.isPending(session), "still pending — neither approved nor denied");
    }

    @Test
    void supersededApprovalIsNotApproved() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        queue.request(session, call(), "destructive", Duration.ofMinutes(5));

        // A waiter already blocked on request #1's latch — supersede releases IT.
        AtomicReference<Boolean> released = new AtomicReference<>(null);
        CountDownLatch waiterDone = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            released.set(queue.awaitDecision(session, 5000));
            waiterDone.countDown();
        });
        waiter.start();
        // Wait until the waiter has entered awaitDecision
        waiterStarted.await();

        // A newer request supersedes the old one
        queue.request(session, new ToolCall("call_2", "terminal", "{}"), "newer destructive");

        // The old waiter was released promptly (not after its 5s timeout)
        assertTrue(waiterDone.await(2, TimeUnit.SECONDS), "supersede must release the old waiter");
        assertEquals(Boolean.TRUE, released.get(), "old latch counted down → released");
        // ...but the visible state is the NEW pending request: still not approved.
        assertFalse(queue.isApproved(session), "superseded → new request pending, still not approved");
        assertTrue(queue.isPending(session));
    }

    @Test
    void explicitApproveIsTheOnlyPathToApproved() {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        queue.request(session, call(), "destructive", Duration.ofMinutes(5));
        queue.approve(session, "approve", null);
        assertTrue(queue.isApproved(session));
        assertFalse(queue.isPending(session));
    }
}
