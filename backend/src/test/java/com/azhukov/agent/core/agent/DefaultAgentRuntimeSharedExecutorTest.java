package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.*;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M17: parallel tool execution must run on a SHARED executor instead of one
 * executor per batch. c2 moved the parallel dispatch into
 * {@link TurnExecutor#executeToolBatch} — the executor under test is now
 * TurnExecutor's {@code parallelToolExecutor}, reached through the runtime's
 * lazily-created executor instance.
 */
class DefaultAgentRuntimeSharedExecutorTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private ToolExecutionService toolExecutionService;
    private ToolRegistry toolRegistry;
    private IterationBudget iterationBudget;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        toolRegistry = mock(ToolRegistry.class);
        toolExecutionService = mock(ToolExecutionService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);
        iterationBudget = mock(IterationBudget.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        ContextReferenceService contextReferenceService = mock(ContextReferenceService.class);
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(1);
        properties.getError().setRetryAttempts(0);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        ToolCallGuardrail guardrail = mock(ToolCallGuardrail.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);
        BackgroundReviewService backgroundReviewService = mock(BackgroundReviewService.class);
        InterruptToken interruptToken = mock(InterruptToken.class);
        TurnFinalizer turnFinalizer = mock(TurnFinalizer.class);
        SteerBuffer steerBuffer = mock(SteerBuffer.class);
        ErrorClassifier errorClassifier = new ErrorClassifier();

        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("system"));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("test_tool", "test tool", java.util.Map.of())
            ));

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);
        // c2: canonical batch executor applies the aggregate tool-result budget.
        when(toolExecutionService.enforceToolResultBudget(any()))
            .thenAnswer(inv -> inv.getArgument(0));

        runtime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    private Session session() {
        return Session.create("test-user", "openai-compatible", "gpt-4");
    }

    /** c2: the shared executor now lives on TurnExecutor (canonical owner). */
    private ExecutorService sharedExecutor() throws Exception {
        TurnExecutor executor = runtime.turnExecutor();
        Field field = TurnExecutor.class.getDeclaredField("parallelToolExecutor");
        field.setAccessible(true);
        return (ExecutorService) field.get(executor);
    }

    @Test
    void hasSharedParallelToolExecutorField() throws Exception {
        assertThat(sharedExecutor()).isInstanceOf(ExecutorService.class);
        // Verify it's not shutdown (i.e., it's a shared, long-lived executor)
        assertThat(sharedExecutor().isShutdown()).isFalse();
    }

    @Test
    void sharedExecutorIsNotClosedAfterToolExecution() throws Exception {
        // Execute a turn with tool calls
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "test_tool", "{}")
            )));
        when(toolExecutionService.execute(anyString(), anyString(), anyString(), any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("result"));

        runtime.runTurn(session(), "test");

        // The shared executor should still be alive after execution
        assertThat(sharedExecutor().isShutdown()).isFalse();
    }

    @Test
    void parallelToolExecutionUsesSameExecutorInstance() throws Exception {
        // Run two turns and verify the executor instance is the same (shared, not recreated)
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "test_tool", "{}")
            )));
        when(toolExecutionService.execute(anyString(), anyString(), anyString(), any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("result"));

        runtime.runTurn(session(), "test1");

        ExecutorService executor1 = sharedExecutor();

        runtime.runTurn(session(), "test2");
        ExecutorService executor2 = sharedExecutor();

        // Same instance — shared executor, not recreated per batch
        assertThat(executor1).isSameAs(executor2);
        assertThat(executor1.isShutdown()).isFalse();
    }
}
