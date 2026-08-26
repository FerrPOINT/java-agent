package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalQueueTest {

    @Test
    void requestApproveAndClear() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        ToolCall call = new ToolCall("t1", "tool", "{\"x\":1}");
        ApprovalQueue.PendingApproval p = q.request(session, call, "reason");
        assertThat(p.approved()).isFalse();
        assertThat(p.denied()).isFalse();
        assertThat(q.getPending(session).reason()).isEqualTo("reason");

        ApprovalQueue.PendingApproval approved = q.approve(session, "approve", "ok");
        assertThat(approved.approved()).isTrue();
        assertThat(approved.denied()).isFalse();
        assertThat(approved.note()).isEqualTo("ok");

        q.clear(session);
        assertThat(q.getPending(session)).isNull();
    }

    @Test
    void approveMissingReturnsNull() {
        ApprovalQueue q = new ApprovalQueue();
        assertThat(q.approve(UUID.randomUUID(), "approve", "n")).isNull();
    }

    @Test
    void onceDecisionApproves() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        assertThat(q.approve(session, "once", "go").approved()).isTrue();
    }

    @Test
    void rejectDecisionDoesNotApprove() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        ApprovalQueue.PendingApproval result = q.approve(session, "reject", "no");
        assertThat(result.approved()).isFalse();
        assertThat(result.denied()).isTrue();
    }

    @Test
    void denyMarksAsDenied() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        q.request(session, new ToolCall("t", "tool", "{}"), "r");
        ApprovalQueue.PendingApproval result = q.deny(session, "dangerous");
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
        q.approve(session, "approve", "ok");
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
        q.approve(s1, "approve", "ok");
        q.deny(s2, "no");
        // Only s3 should be pending
        assertThat(q.getPendingApprovals()).hasSize(1);
        assertThat(q.getPendingApprovals().get(0).sessionId()).isEqualTo(s3);
    }

    @Test
    void timeoutAutoDeniesExpiredApprovals() {
        ApprovalQueue q = new ApprovalQueue();
        UUID session = UUID.randomUUID();
        // Request with a 1-nanosecond timeout so it's already expired
        q.request(session, new ToolCall("t", "tool", "{}"), "r", Duration.ofNanos(1));
        // Give it time to expire
        // timing-assertion: verifies expiry after nanosecond timeout
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        q.timeout(Duration.ofMinutes(5));
        assertThat(q.isDenied(session)).isTrue();
        assertThat(q.getPending(session).note()).contains("timeout");
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