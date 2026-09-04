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
        Thread waiter = new Thread(() -> {
            released.set(queue.awaitDecision(session, 5000));
            waiterDone.countDown();
        });
        waiter.start();
        // Deterministic sync: spin until the waiter is actually inside
        // awaitDecision's latch.await (waiterStarted alone races — the thread may
        // not have reached the latch yet when request() supersedes, and the old
        // latch is then counted down before anyone waits on it).
        awaitBlocked(waiter);

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

    /**
     * rev-131 Hermes parity ('once' semantics, approval.py:4368): an approval
     * is single-use. The runtime consumes it (clear) with the execution it
     * authorized — after consumption a NEW dangerous call must re-prompt
     * (isPending true again after requestApproval), not sail through on the
     * stale approved entry.
     */
    @Test
    void approvalIsSingleUse_ConsumedByExecution() {
        ApprovalQueue queue = new ApprovalQueue();
        UUID session = UUID.randomUUID();

        // First dangerous call → prompt → user approves → gate consumes.
        queue.request(session, new ToolCall("c1", "delete_file", "{}"), "destructive", Duration.ofMinutes(5));
        queue.approve(session, "approve", null);
        assertTrue(queue.isApproved(session), "approved before execution");
        queue.clear(session); // runtime consumes (rev-131)

        // Second dangerous call: guardrail fires again, queue re-prompts.
        assertFalse(queue.isApproved(session), "consumed approval must not linger");
        assertNull(queue.getPending(session), "consumed entry fully removed");
        queue.request(session, new ToolCall("c2", "terminal", "{}"), "another destructive", Duration.ofMinutes(5));
        assertTrue(queue.isPending(session), "fresh dangerous call must re-prompt");
        assertFalse(queue.isApproved(session), "re-prompt starts undecided");
    }

    /**
     * Spins until the waiter thread is parked inside CountDownLatch.await
     * (TIMED_WAITING on the latch) — the only state from which a supersede
     * countDown() is guaranteed to release it.
     */
    private static void awaitBlocked(Thread waiter) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            StackTraceElement[] stack = waiter.getStackTrace();
            for (StackTraceElement frame : stack) {
                if (frame.getClassName().equals("java.util.concurrent.CountDownLatch")
                        && frame.getMethodName().equals("await")) {
                    return;
                }
            }
            if (!waiter.isAlive()) {
                return; // finished early (e.g. released) — assertions below still hold
            }
            Thread.sleep(5);
        }
        throw new AssertionError("waiter never reached CountDownLatch.await");
    }
}
