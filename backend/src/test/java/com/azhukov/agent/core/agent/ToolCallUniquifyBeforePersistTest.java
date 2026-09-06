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
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4-audit parity (conversation_loop.py:7071 → :7141 → :7424): tool-call ids are
 * uniquified BEFORE the assistant message is built and persisted. The persisted
 * assistant row, the executed calls, and the tool results must all share ids;
 * otherwise replay sanitizers drop the later results of duplicated ids.
 */
class ToolCallUniquifyBeforePersistTest {

    private AgentProperties makeProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("core");
        properties.getCore().setMaxTurns(10);
        properties.getCore().setEmptyBackoffBaseMs(1L);
        properties.getCore().setEmptyBackoffCapMs(2L);
        return properties;
    }

    @Test
    @DisplayName("Duplicate tool-call ids are uniquified in the persisted assistant row and results")
    void duplicateIdsUniquifiedBeforePersist() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolDefinition webSearch = new ToolDefinition("web_search", "search", java.util.Map.of());
        when(registry.getDefinitions(any())).thenReturn(List.of(webSearch));
        when(registry.getDefinitions(anySet())).thenReturn(List.of(webSearch));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.buildSystemMessage(any())).thenReturn(Message.system("sys"));
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));

        // Model reuses ONE id for two different calls (real provider behavior),
        // then finishes with plain text on the next round.
        java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
        ModelClient model = (msgs, tools, opts) -> rounds.incrementAndGet() == 1
            ? ChatResponse.toolCalls(List.of(
                new ToolCall("dup-id", "web_search", "{\"query\":\"a\"}"),
                new ToolCall("dup-id", "web_search", "{\"query\":\"b\"}")))
            : ChatResponse.text("done");

        List<String> executedCallIds = new ArrayList<>();
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.execute(any(String.class), any(String.class),
                any(String.class), any(), any(), any()))
            .thenAnswer(inv -> {
                executedCallIds.add(inv.getArgument(1));
        when(toolExecutionService.enforceToolResultBudget(any()))
            .thenAnswer(b -> b.getArgument(0));
                return ToolResult.ok("result");
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

        List<Message> persisted = new ArrayList<>();
        MidTurnPersistenceCallback recording = (sessionId, messages, from) -> {
            persisted.addAll(messages.subList(from, messages.size()));
            return true;
        };

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            mock(MessageSanitizer.class), contextReferenceService, properties,
            mock(UserInputSanitizer.class), guardrail, turnStateManager, null,
            mock(InterruptToken.class), null, mock(SteerBuffer.class), null, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), recording, null);

        runtime.runTurn(Session.create("user", "noop", "noop"), "search two things");

        // 1. The persisted assistant row carries DISTINCT ids for both calls.
        Message persistedAssistant = persisted.stream()
            .filter(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty())
            .findFirst().orElseThrow();
        Set<String> persistedIds = new HashSet<>();
        persistedAssistant.toolCalls().forEach(tc -> persistedIds.add(tc.id()));
        assertThat(persistedAssistant.toolCalls()).hasSize(2);
        assertThat(persistedIds).hasSize(2); // "dup-id" became two unique ids

        // 2. The executed calls used the same uniquified ids as the persisted row.
        assertThat(executedCallIds).containsExactlyInAnyOrder("dup-id", "dup-id_d2");
        assertThat(persistedIds).containsAll(executedCallIds);

        // 3. Tool result ids match the persisted assistant call ids.
        List<String> resultIds = persisted.stream()
            .filter(m -> m.role() == Role.TOOL)
            .map(Message::toolCallId)
            .toList();
        assertThat(resultIds).containsExactlyInAnyOrder("dup-id", "dup-id_d2");
        assertThat(persistedIds).containsAll(resultIds);
    }
}
