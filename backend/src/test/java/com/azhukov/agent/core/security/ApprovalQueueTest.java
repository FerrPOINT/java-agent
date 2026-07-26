package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

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
        assertThat(q.getPending(session).reason()).isEqualTo("reason");

        ApprovalQueue.PendingApproval approved = q.approve(session, "approve", "ok");
        assertThat(approved.approved()).isTrue();
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
        assertThat(q.approve(session, "reject", "no").approved()).isFalse();
    }
}
