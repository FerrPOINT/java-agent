package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F16: the approval flow was dead code — DefaultToolGuardrails.requiresApproval /
 * requestApproval had zero callers, so no request was ever created and
 * ApprovalQueue.isPending() was always false in both runtimes. These tests pin
 * the producer path the runtimes now call.
 */
class ApprovalProducerWiringTest {

    private AgentProperties propsWith(String tool) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        p.getSecurity().setAlwaysRequireApprovalTools(List.of(tool));
        return p;
    }

    @Test
    void guardrailFlagsDestructiveTool() {
        DefaultToolGuardrails g = new DefaultToolGuardrails(propsWith("terminal"), new ApprovalQueue());
        assertTrue(g.requiresApproval(new ToolCall("c1", "terminal", "{\"command\":\"rm -rf /\"}")));
        assertFalse(g.requiresApproval(new ToolCall("c2", "read_file", "{}")));
    }

    @Test
    void requestApprovalEnqueuesPending() {
        ApprovalQueue queue = new ApprovalQueue();
        DefaultToolGuardrails g = new DefaultToolGuardrails(propsWith("terminal"), queue);
        UUID session = UUID.randomUUID();
        ToolCall call = new ToolCall("c1", "terminal", "{\"command\":\"reboot\"}");

        ApprovalQueue.PendingApproval created = g.requestApproval(session, call);
        assertNotNull(created, "requestApproval must enqueue the request");
        assertTrue(queue.isPending(session), "producer must make isPending true — was always false before F16");
        assertFalse(queue.isApproved(session));
    }

    @Test
    void approvalsDisabledMeansNoGate() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(false);
        DefaultToolGuardrails g = new DefaultToolGuardrails(p, new ApprovalQueue());
        assertFalse(g.requiresApproval(new ToolCall("c1", "terminal", "{}")));
    }

    @Test
    void fullCycleRequestWaitApproveExecute() throws Exception {
        ApprovalQueue queue = new ApprovalQueue();
        DefaultToolGuardrails g = new DefaultToolGuardrails(propsWith("terminal"), queue);
        UUID session = UUID.randomUUID();

        // Runtime-side gate sequence (as wired in TurnExecutor/DefaultAgentRuntime):
        ToolCall call = new ToolCall("c1", "terminal", "{\"command\":\"shutdown now\"}");
        boolean approvalRequired = queue.isPending(session)
            || (g.requiresApproval(call) && queue.getPending(session) == null && g.requestApproval(session, call) != null);
        assertTrue(approvalRequired, "gate must engage for a destructive tool");

        // Approve from the user side while the runtime waits
        Thread approver = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            queue.approve(session, "approve", null);
        });
        approver.start();

        long t0 = System.currentTimeMillis();
        boolean decided = queue.awaitDecision(session, 5000);
        long waited = System.currentTimeMillis() - t0;
        assertTrue(decided, "latch must release on approve");
        assertTrue(waited < 3000, "waiter released promptly, not by timeout");
        assertTrue(queue.isApproved(session), "post-wait re-validation sees approved → execute");
        approver.join(1000);
    }
}
