package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6 parity (Hermes conversation_loop.py:7437-7449, 7474-7478): when mid-turn
 * persistence of the assistant tool-call message fails, side-effecting tools
 * must NOT run from process-only state; the turn aborts with the
 * session_persistence_failed exit reason instead. When tool-RESULT persistence
 * fails after execution, the turn aborts before the next model call.
 */
class PersistenceFailureAbortsToolExecutionTest {

    private AgentProperties makeProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("core");
        properties.getCore().setMaxTurns(10);
        properties.getCore().setEmptyBackoffBaseMs(1L);
        properties.getCore().setEmptyBackoffCapMs(2L);
        return properties;
    }

    private DefaultAgentRuntime buildRuntime(MidTurnPersistenceCallback persistence,
                                             AtomicInteger toolExecutions) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("write_file", "writes a file", java.util.Map.of())));
        when(registry.getDefinitions(anySet())).thenReturn(List.of(
            new ToolDefinition("write_file", "writes a file", java.util.Map.of())));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.buildSystemMessage(any())).thenReturn(Message.system("sys"));
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));

        ModelClient model = (msgs, tools, opts) -> ChatResponse.toolCalls(List.of(
            new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/x\",\"content\":\"boom\"}")));

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.execute(any(String.class), any(String.class),
                any(String.class), any(), any(), any()))
            .thenAnswer(inv -> {
                toolExecutions.incrementAndGet();
                return ToolResult.ok("written");
            });

        IterationBudget iterationBudget = mock(IterationBudget.class);
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);

        TurnStateManager turnStateManager = mock(TurnStateManager.class);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        ToolCallGuardrail guardrail = mock(ToolCallGuardrail.class);
        when(guardrail.isHalted()).thenReturn(false);

        var contextReferenceService = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(contextReferenceService.resolve(any())).thenReturn(List.of());
        when(contextReferenceService.loadContent(any())).thenReturn(java.util.Optional.empty());

        return new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            mock(MessageSanitizer.class), contextReferenceService, properties,
            mock(UserInputSanitizer.class), guardrail, turnStateManager, null,
            mock(InterruptToken.class), null, mock(SteerBuffer.class), null, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), persistence, null);
    }

    @Test
    @DisplayName("Assistant persistence failure aborts the turn before tool execution")
    void assistantPersistenceFailureSkipsTools() {
        AtomicInteger toolExecutions = new AtomicInteger();
        MidTurnPersistenceCallback failing = (sessionId, messages, from) -> false;

        DefaultAgentRuntime runtime = buildRuntime(failing, toolExecutions);
        var result = runtime.runTurn(Session.create("user", "noop", "noop"), "write a file");

        assertThat(toolExecutions.get()).isZero();
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("persistence failed");
        // The assistant tool-call row stays in the transcript so the user can see
        // what was attempted, but nothing was executed.
        assertThat(result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty())
            .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tool-result persistence failure aborts the turn (no next model call)")
    void toolResultPersistenceFailureAbortsTurn() {
        AtomicInteger toolExecutions = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        // first persist (assistant) ok, second (results) fails
        MidTurnPersistenceCallback failingOnResults = (sessionId, messages, from) ->
            calls.incrementAndGet() == 1;

        DefaultAgentRuntime runtime = buildRuntime(failingOnResults, toolExecutions);
        var result = runtime.runTurn(Session.create("user", "noop", "noop"), "write a file");

        assertThat(toolExecutions.get()).isEqualTo(1); // tools DID run (assistant was persisted)
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("persistence failed");
    }
}
