package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for commentary emission in the non-streaming path
 * ({@link DefaultAgentRuntime} + {@link CommentaryCallback}).
 * <p>
 * S-3 gap: when the LLM returns BOTH visible text AND tool calls, the text
 * is "commentary" — an interim assistant message shown to the user before
 * tool execution. The {@link CommentaryCallback} is invoked with
 * {@code alreadyStreamed=false} in the non-streaming path.
 * <p>
 * Streaming path tests are in {@code CommentaryStreamingTest} (service package).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentaryCallbackTest {

    private ModelClient modelClient;
    private ToolRegistry toolRegistry;
    private ToolExecutionService toolExecutionService;
    private AgentProperties properties;
    private CommentaryCallback commentaryCallback;
    private DefaultAgentRuntime runtime;

    @BeforeEach
    void setUpNonStreaming() {
        modelClient = mock(ModelClient.class);
        toolRegistry = mock(ToolRegistry.class);
        toolExecutionService = mock(ToolExecutionService.class);
        commentaryCallback = mock(CommentaryCallback.class);

        properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryAttempts(3);
        // commentaryEnabled defaults to true

        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);
        IterationBudget iterationBudget = mock(IterationBudget.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        ContextReferenceService contextReferenceService = mock(ContextReferenceService.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        ToolCallGuardrail guardrail = mock(ToolCallGuardrail.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);
        InterruptToken interruptToken = mock(InterruptToken.class);
        TurnFinalizer turnFinalizer = mock(TurnFinalizer.class);
        SteerBuffer steerBuffer = mock(SteerBuffer.class);
        ErrorClassifier errorClassifier = new ErrorClassifier();

        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);

        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(TurnState.class));
        // L3: The runtime calls isHalted(session.id()), not isHalted() — fix the matcher
        when(guardrail.isHalted(any(UUID.class))).thenReturn(false);

        runtime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, null,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, commentaryCallback);
    }

    @Test
    @DisplayName("1. commentaryCallback.onCommentary(sessionId, text, false) called when LLM returns text AND tool calls")
    void commentaryCallbackCalled_whenTextAndToolCalls() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger callCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    // First call: text AND tool calls → commentary
                    return new ChatResponse("Let me check the weather.", List.of(toolCall));
                }
                // Second call: text only → final answer
                return ChatResponse.text("The weather is sunny.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        assertThat(result.completed()).isTrue();
        verify(commentaryCallback).onCommentary(session.id(), "Let me check the weather.", false);
    }

    @Test
    @DisplayName("2. commentaryCallback NOT called when LLM returns only text (final response, no tool calls)")
    void commentaryCallbackNotCalled_whenTextOnly() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("Direct answer, no tools needed."));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        verify(commentaryCallback, never()).onCommentary(any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("3. commentaryCallback NOT called when LLM returns only tool calls (no text)")
    void commentaryCallbackNotCalled_whenToolCallsOnly() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");
        AtomicInteger callCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    return ChatResponse.toolCalls(List.of(toolCall));
                }
                return ChatResponse.text("Done.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Weather?");

        assertThat(result.completed()).isTrue();
        verify(commentaryCallback, never()).onCommentary(any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("4. commentaryCallback NOT called when commentaryEnabled=false")
    void commentaryCallbackNotCalled_whenCommentaryDisabled() {
        properties.setCommentaryEnabled(false);

        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Berlin\"}");
        AtomicInteger callCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    return new ChatResponse("Checking weather.", List.of(toolCall));
                }
                return ChatResponse.text("Sunny in Berlin.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Weather?");

        assertThat(result.completed()).isTrue();
        verify(commentaryCallback, never()).onCommentary(any(), anyString(), anyBoolean());
    }
}