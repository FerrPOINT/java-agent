package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalQueueTest {

    @Test
    void lateDecisionAndOldWaiterCannotAuthorizeOrRemoveReplacement() {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var first = queue.request(session, new ToolCall("a", "terminal", "{\"command\":\"first\"}"), "first");
        var second = queue.request(session, new ToolCall("b", "delete_file", "{\"path\":\"second\"}"), "second");
        assertThat(queue.approve(first.requestId(), null)).isNull();
        assertThat(queue.deny(first.requestId(), null)).isNull();
        assertThat(queue.awaitDecision(first, 0)).isFalse();
        assertThat(queue.consumeDecision(first)).isNull();
        assertThat(queue.getPending(session)).isSameAs(second);
        assertThat(queue.isPending(session)).isTrue();
        queue.approve(second.requestId(), "second only");
        var decision = queue.consumeDecision(second);
        assertThat(decision.approved()).isTrue();
        assertThat(decision.call()).isEqualTo(second.call());
        assertThat(queue.consumeDecision(second)).isNull();
    }

    @Test
    void approvedRequestCannotAuthorizeLaterToolEvenWhenArgumentsAreIdentical() {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var call = new ToolCall("a", "terminal", "{}");
        var first = queue.request(session, call, "first");
        queue.approve(first.requestId(), null);
        var second = queue.request(session, call, "second");
        assertThat(queue.consumeDecision(first)).isNull();
        assertThat(queue.getPending(session)).isSameAs(second);
        assertThat(queue.consumeDecision(second)).isNull();
    }

    @Test
    void directDenialCannotReplaceConfirmedApproval() {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var request = queue.request(session, new ToolCall("fixture", "fixture", "{}"), "fixture");
        var approved = queue.approve(request.requestId(), "fixture approval");
        assertThat(queue.deny(request.requestId(), "late denial")).isSameAs(approved);
        assertThat(queue.isApproved(session)).isTrue();
    }

    @Test
    void concurrentDirectDecisionsHaveOneOutcome() throws Exception {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var request = queue.request(session, new ToolCall("fixture", "fixture", "{}"), "fixture");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var approved = executor.submit(() -> {
                start.await();
                return queue.approve(request.requestId(), "fixture approval");
            });
            var denied = executor.submit(() -> {
                start.await();
                return queue.deny(request.requestId(), "fixture denial");
            });
            start.countDown();
            var first = approved.get(2, java.util.concurrent.TimeUnit.SECONDS);
            var second = denied.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first).isSameAs(second);
            assertThat(queue.getPending(session)).isSameAs(first);
            assertThat(queue.awaitDecision(request, 0)).isTrue();
        }
    }

    @Test
    void expiredApprovalsAreNotListedAsPending() {
        var queue = new ApprovalQueue();
        var expired = queue.request(UUID.randomUUID(), new ToolCall("expired", "terminal", "{}"), "expired", Duration.ZERO);
        var current = queue.request(UUID.randomUUID(), new ToolCall("current", "terminal", "{}"), "current");
        assertThat(queue.getPendingApprovals()).containsExactly(current).doesNotContain(expired);
    }

    @Test
    void directApprovalHonorsTheRequestDeadline() {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var request = queue.request(session, new ToolCall("fixture", "fixture", "{}"), "fixture",
            Duration.ofSeconds(-1));
        queue.approve(request.requestId(), null);
        assertThat(queue.isApproved(session)).isFalse();
    }

    @Test
    void directApprovalCannotReplaceConfirmedDenial() {
        var queue = new ApprovalQueue();
        var session = UUID.randomUUID();
        var request = queue.request(session, new ToolCall("fixture", "fixture", "{}"), "fixture");
        queue.deny(request.requestId(), "fixture denial");
        queue.approve(request.requestId(), null);
        assertThat(queue.isDenied(session)).isTrue();
        assertThat(queue.isApproved(session)).isFalse();
    }

    @Test
    void independentSessionCanRequestApprovalWhileFirstDecisionPersists() throws Exception {
        var queue = new ApprovalQueue();
        var call = new ToolCall("fixture", "fixture", "{}");
        UUID first = new UUID(0, 0);
        UUID second = new UUID(1, 1);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        var pending = queue.request(first, call, "first");
        var persisting = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var decision = executor.submit(() -> queue.resolvePending(pending, true, () -> {
                persisting.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            try {
                assertThat(persisting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var independent = executor.submit(() -> queue.request(second, call, "second"));
                assertThat(independent.get(1, java.util.concurrent.TimeUnit.SECONDS).sessionId()).isEqualTo(second);
            } finally {
                release.countDown();
                assertThat(decision.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void timedOutApprovalClearsWithoutWaitingForPersistence() throws Exception {
        var queue = new ApprovalQueue();
        var call = new ToolCall("fixture", "fixture", "{}");
        UUID first = new UUID(0, 0);
        var pending = queue.request(first, call, "first");
        var persisting = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var decision = executor.submit(() -> queue.resolvePending(pending, true, () -> {
                persisting.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            try {
                assertThat(persisting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var waiting = executor.submit(() -> {
                    boolean decided = queue.awaitDecision(pending, 50);
                    assertThat(queue.consumeDecision(pending)).isNull();
                    return decided;
                });
                assertThat(waiting.get(1, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
                assertThat(queue.getPending(first)).isNull();
            } finally {
                release.countDown();
                assertThat(decision.get(2, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            }
        }
    }

    @Test
    void requestApproveAndClear() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        ToolCall call = new ToolCall("t1", "tool", "{\"x\":1}");
        ApprovalQueue.PendingApproval p = q.request(session, call, "reason");
        assertThat(p.approved()).isFalse();
        assertThat(p.denied()).isFalse();
        assertThat(q.getPending(session).reason()).isEqualTo("reason");

        ApprovalQueue.PendingApproval approved = q.approve(q.getPending(session).requestId(), "ok");
        assertThat(approved.approved()).isTrue();
        assertThat(approved.denied()).isFalse();
        assertThat(approved.note()).isEqualTo("ok");

        q.clear(session);
        assertThat(q.getPending(session)).isNull();
    }

    @Test
    void approveMissingReturnsNull() {
        ApprovalQueue q = new ApprovalQueue();
        assertThat(q.approve(UUID.randomUUID(), "n")).isNull();
    }

    @Test
    void exactRequestApproves() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        assertThat(q.approve(q.getPending(session).requestId(), "go").approved()).isTrue();
    }

    @Test
    void explicitDenialDoesNotApprove() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        ApprovalQueue.PendingApproval result = q.deny(q.getPending(session).requestId(), "no");
        assertThat(result.approved()).isFalse();
        assertThat(result.denied()).isTrue();
    }

    @Test
    void denyMarksAsDenied() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        ApprovalQueue.PendingApproval result = q.deny(q.getPending(session).requestId(), "dangerous");
        assertThat(result.approved()).isFalse();
        assertThat(result.denied()).isTrue();
        assertThat(result.note()).isEqualTo("dangerous");
        assertThat(q.isDenied(session)).isTrue();
        assertThat(q.isApproved(session)).isFalse();
        assertThat(q.isPending(session)).isFalse();
    }

    @Test
    void isPendingTrueForNewRequest() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        assertThat(q.isPending(session)).isTrue();
        assertThat(q.isApproved(session)).isFalse();
        assertThat(q.isDenied(session)).isFalse();
    }

    @Test
    void isApprovedTrueAfterApprove() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        q.approve(q.getPending(session).requestId(), "ok");
        assertThat(q.isApproved(session)).isTrue();
        assertThat(q.isPending(session)).isFalse();
    }

    @Test
    void getPendingApprovalsListsOnlyUndecided() {
        ApprovalQueue q = new ApprovalQueue();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        q.request(s1, new ToolCall("t", "tool", "{}"), "r1");
        q.request(s2, new ToolCall("t", "tool", "{}"), "r2");
        q.request(s3, new ToolCall("t", "tool", "{}"), "r3");
        q.approve(q.getPending(s1).requestId(), "ok");
        q.deny(q.getPending(s2).requestId(), "no");
        // Only s3 should be pending
        assertThat(q.getPendingApprovals()).hasSize(1);
        assertThat(q.getPendingApprovals().get(0).sessionId()).isEqualTo(s3);
    }

    @Test
    void getPendingAutoDeniesExpired() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r", Duration.ofNanos(1));
        // timing-assertion: verifies expiry after nanosecond timeout
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ApprovalQueue.PendingApproval p = q.getPending(session);
        assertThat(p.denied()).isTrue();
        assertThat(p.note()).contains("timeout");
    }
}
