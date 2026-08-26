package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.ToolGuardrails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P9 parity (Hermes tool_executor.py:661,726): every dispatched tool — including
 * members of a parallel-safe batch — must traverse the approval gate before
 * execution. A batch containing an approval-required tool is forced onto the
 * sequential path so request/wait/fail-closed validation actually runs.
 */
class ParallelApprovalGateParityTest {

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        p.getSecurity().getAlwaysRequireApprovalTools().clear();
        p.getSecurity().getAlwaysRequireApprovalTools().add("web_search");
        return p;
    }

    private TurnExecutor executor(AgentProperties props, ToolGuardrails guardrails,
                                  ApprovalQueue queue, ToolExecutionService svc) {
        return new TurnExecutor(
            mock(com.azhukov.agent.client.langchain4j.ErrorClassifier.class),
            props, null, null, svc,
            new ToolResultFormatter(), new TokenEstimator(),
            new InterruptToken(), queue, guardrails,
            mock(MemoryNudgeManager.class), new SteerBuffer());
    }

    @Test
    @DisplayName("Approval-required tool in a parallel-safe batch runs sequentially through the gate")
    void approvalRequiredForcesSequentialGate() {
        AgentProperties props = properties();
        ApprovalQueue queue = mock(ApprovalQueue.class);
        // Make the gate fire: requiresApproval returns true, requestApproval
        // creates a pending entry, then awaitDecision times out (fail-closed).
        when(queue.getPending(any())).thenReturn(new ApprovalQueue.PendingApproval(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            new ToolCall("pending", "web_search", "{}"), "test", java.time.Instant.now(),
            false, false, null, java.time.Instant.now().plusSeconds(60)));
        when(queue.isPending(any())).thenReturn(true);
        when(queue.awaitDecision(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        when(queue.isApproved(any())).thenReturn(false);
        when(queue.isDenied(any())).thenReturn(false);
        ToolGuardrails guardrails = new com.azhukov.agent.core.security.DefaultToolGuardrails(props, queue);
        AtomicInteger executions = new AtomicInteger();
        ToolExecutionService svc = mock(ToolExecutionService.class);
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                executions.incrementAndGet();
                return ToolResult.ok("done");
            });
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));

        TurnExecutor turnExecutor = executor(props, guardrails, queue, svc);
        Session session = Session.create("user", "noop", "noop");
        List<ToolCall> calls = List.of(
            new ToolCall("c1", "web_search", "{\"query\":\"a\"}"),
            new ToolCall("c2", "web_search", "{\"query\":\"b\"}"));
        Set<String> registered = Set.of("web_search");

        var result = turnExecutor.executeToolBatch(calls, registered, session,
            mock(TurnState.class), 1, false);

        // Sequential gate ran: a pending approval was created and the wait
        // timed out (no user decision) → fail-closed, no execution.
        assertThat(executions.get()).isZero();
        assertThat(result.toolResults()).isNotEmpty();
        String firstResult = result.toolResults().get(0).content();
        assertThat(firstResult.contains("blocked")
            || firstResult.contains("denied")
            || firstResult.contains("approval")).isTrue();
    }

    @Test
    @DisplayName("Without approval-requiring tools the parallel path still applies")
    void nonApprovalBatchStillParallelizes() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setApprovalsEnabled(true);
        props.getSecurity().getAlwaysRequireApprovalTools().clear();
        ApprovalQueue queue = new ApprovalQueue();
        ToolGuardrails guardrails = new com.azhukov.agent.core.security.DefaultToolGuardrails(props, queue);
        AtomicInteger executions = new AtomicInteger();
        ToolExecutionService svc = mock(ToolExecutionService.class);
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                executions.incrementAndGet();
                return ToolResult.ok("done");
            });
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));

        TurnExecutor turnExecutor = executor(props, guardrails, queue, svc);
        Session session = Session.create("user", "noop", "noop");
        List<ToolCall> calls = List.of(
            new ToolCall("c1", "web_search", "{\"query\":\"a\"}"),
            new ToolCall("c2", "web_search", "{\"query\":\"b\"}"));
        Set<String> registered = Set.of("web_search");

        var result = turnExecutor.executeToolBatch(calls, registered, session,
            mock(TurnState.class), 1, false);

        assertThat(executions.get()).isEqualTo(2);
        assertThat(result.isInterrupted()).isFalse();
        assertThat(result.toolResults()).hasSize(2);
    }
}
