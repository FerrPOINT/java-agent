package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.ToolGuardrails;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.tool.ToolExecutionService;
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
 * c2 regression tests for the canonical batch executor
 * ({@link TurnExecutor#executeToolBatch}) — both the sync runtime and the SSE
 * streaming loop dispatch through it, so its gate semantics are the single
 * source of behavioural truth for tool approval on every surface.
 */
class TurnExecutorBatchGateRegressionTest {

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        p.getSecurity().getAlwaysRequireApprovalTools().clear();
        p.getSecurity().getAlwaysRequireApprovalTools().add("terminal");
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
    @DisplayName("fail-closed: guardrail flags the tool but requestApproval returns null → deny, never execute")
    void nullApprovalProducerDeniesInsteadOfExecuting() {
        AgentProperties props = properties();
        ApprovalQueue queue = mock(ApprovalQueue.class);
        ToolGuardrails guardrails = mock(ToolGuardrails.class);
        ToolExecutionService svc = mock(ToolExecutionService.class);
        AtomicInteger executions = new AtomicInteger();
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> { executions.incrementAndGet(); return ToolResult.ok("x"); });
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));
        // Guardrail flags the tool, but the producer returns null (e.g. approvals
        // UI unavailable). Old gate: requestApproval(...)!=null → false → EXECUTED.
        when(guardrails.requiresApproval(any(ToolCall.class))).thenReturn(true);
        when(guardrails.requestApproval(any(UUID.class), any(ToolCall.class))).thenReturn(null);
        when(queue.isPending(any())).thenReturn(false);
        when(queue.getPending(any())).thenReturn(null);
        when(queue.awaitDecision(any(UUID.class), any(long.class))).thenReturn(false);
        when(queue.isApproved(any())).thenReturn(false);

        TurnExecutor executor = executor(props, guardrails, queue, svc);
        Session session = Session.create("user", "noop", "noop");
        List<ToolCall> calls = List.of(new ToolCall("c1", "terminal", "{\"command\":\"rm -rf /\"}"));

        var result = executor.executeToolBatch(calls, Set.of("terminal"), session,
            mock(TurnState.class), 1, false, null);

        assertThat(executions.get()).as("null producer must fail closed, not execute").isZero();
        assertThat(result.toolResults()).isNotEmpty();
        assertThat(result.toolResults().get(0).content()).contains("fail-closed");
    }

    @Test
    @DisplayName("skipApproval=true (yolo/subagent-auto-approve) bypasses the gate and executes")
    void skipApprovalBypassesGate() {
        AgentProperties props = properties();
        ApprovalQueue queue = mock(ApprovalQueue.class);
        ToolGuardrails guardrails = mock(ToolGuardrails.class);
        when(guardrails.requiresApproval(any(ToolCall.class))).thenReturn(true);
        ToolExecutionService svc = mock(ToolExecutionService.class);
        AtomicInteger executions = new AtomicInteger();
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> { executions.incrementAndGet(); return ToolResult.ok("done"); });
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));

        TurnExecutor executor = executor(props, guardrails, queue, svc);
        Session session = Session.create("user", "noop", "noop");
        List<ToolCall> calls = List.of(new ToolCall("c1", "terminal", "{\"command\":\"pwd\"}"));

        var result = executor.executeToolBatch(calls, Set.of("terminal"), session,
            mock(TurnState.class), 1, true, null);

        assertThat(executions.get()).isEqualTo(1);
        assertThat(result.isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("subagent child session auto-denies dangerous calls without waiting")
    void subagentChildAutoDenies() {
        AgentProperties props = properties();
        ApprovalQueue queue = mock(ApprovalQueue.class);
        ToolGuardrails guardrails = mock(ToolGuardrails.class);
        when(guardrails.requiresApproval(any(ToolCall.class))).thenReturn(true);
        ToolExecutionService svc = mock(ToolExecutionService.class);
        AtomicInteger executions = new AtomicInteger();
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> { executions.incrementAndGet(); return ToolResult.ok("done"); });
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));

        TurnExecutor executor = executor(props, guardrails, queue, svc);
        Session child = Session.create("user", "noop", "noop")
            .withMetadata("delegation_parent_session", "11111111-1111-1111-1111-111111111111");
        List<ToolCall> calls = List.of(new ToolCall("c1", "terminal", "{\"command\":\"pwd\"}"));

        var result = executor.executeToolBatch(calls, Set.of("terminal"), child,
            mock(TurnState.class), 1, false, null);

        assertThat(executions.get()).as("subagent must never execute gated tools").isZero();
        assertThat(result.toolResults().get(0).content()).contains("subagent policy");
    }

    @Test
    @DisplayName("execute_code-only batch records refund flags for budget accounting")
    void executeCodeRefundRecorded() {
        AgentProperties props = properties();
        ToolExecutionService svc = mock(ToolExecutionService.class);
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(ToolResult.ok("42"));
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));
        TurnExecutor executor = executor(props, mock(ToolGuardrails.class), null, svc);

        Session session = Session.create("user", "noop", "noop");
        List<ToolCall> calls = List.of(new ToolCall("c1", "execute_code", "{\"code\":\"1+1\"}"));

        var result = executor.executeToolBatch(calls, Set.of("execute_code"), session,
            mock(TurnState.class), 1, false, null);

        assertThat(result.executions()).hasSize(1);
        assertThat(result.executions().get(0).toolName()).isEqualTo("execute_code");
        assertThat(result.executions().get(0).refunded()).isTrue();

        // Mixed batch: no refund
        List<ToolCall> mixed = List.of(
            new ToolCall("c1", "execute_code", "{\"code\":\"1+1\"}"),
            new ToolCall("c2", "read_file", "{\"path\":\"/tmp/x\"}"));
        var mixedResult = executor.executeToolBatch(mixed,
            Set.of("execute_code", "read_file"), session, mock(TurnState.class), 1, false, null);
        assertThat(mixedResult.executions())
            .allSatisfy(rec -> assertThat(rec.refunded()).isFalse());
    }

    @Test
    @DisplayName("steer note is appended AFTER budget enforcement and survives in the result")
    void steerInjectedAfterBudgetEnforcement() {
        AgentProperties props = properties();
        ToolExecutionService svc = mock(ToolExecutionService.class);
        when(svc.execute(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(ToolResult.ok("long result ".repeat(100)));
        // Budget enforcement simulates truncation of the LAST message; the steer
        // must still be present in the returned last tool result.
        when(svc.enforceToolResultBudget(any())).thenAnswer(inv -> {
            List<Message> msgs = inv.getArgument(0);
            if (!msgs.isEmpty()) {
                Message last = msgs.get(msgs.size() - 1);
                msgs.set(msgs.size() - 1, Message.toolResult(last.toolCallId(),
                    "[truncated]", last.turnIndex()));
            }
            return msgs;
        });
        SteerBuffer steerBuffer = new SteerBuffer();
        TurnExecutor executor = new TurnExecutor(
            mock(com.azhukov.agent.client.langchain4j.ErrorClassifier.class),
            props, null, null, svc, new ToolResultFormatter(), new TokenEstimator(),
            new InterruptToken(), null, null, null, steerBuffer);

        Session session = Session.create("user", "noop", "noop");
        UUID sessionId = session.id();
        steerBuffer.steer(sessionId, "focus on step 2");

        List<ToolCall> calls = List.of(new ToolCall("c1", "weather", "{\"city\":\"Paris\"}"));
        var result = executor.executeToolBatch(calls, Set.of("weather"), session,
            mock(TurnState.class), 1, false, null);

        Message last = result.toolResults().get(result.toolResults().size() - 1);
        assertThat(last.content()).contains("focus on step 2");
        assertThat(steerBuffer.hasPending(sessionId)).isFalse();
    }
}
