package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.BackgroundReviewService;
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

import java.util.HashSet;
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
 * Tests for think-block handling in {@link DefaultAgentRuntime}.
 * <p>
 * Covers:
 * <ul>
 *   <li>Tag variant stripping (⇋, &lt;thinking&gt;, &lt;reasoning&gt;, &lt;thought&gt;, &lt;REASONING_SCRATCHPAD&gt;)</li>
 *   <li>Thinking-only prefill continuation (up to 2 retries)</li>
 *   <li>Thinking budget exhaustion (terminal condition)</li>
 *   <li>Incomplete scratchpad handling (up to 2 retries)</li>
 *   <li>Stripping think blocks from final output</li>
 * </ul>
 */
class DefaultAgentRuntimeThinkBlockTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private ToolExecutionService toolExecutionService;
    private ToolRegistry toolRegistry;
    private IterationBudget iterationBudget;
    private TurnStateManager turnStateManager;
    private TurnFinalizer turnFinalizer;
    private InterruptToken interruptToken;
    private SteerBuffer steerBuffer;
    private BackgroundReviewService backgroundReviewService;
    private ToolCallGuardrail guardrail;
    private ErrorClassifier errorClassifier;

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
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryAttempts(3);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        guardrail = mock(ToolCallGuardrail.class);
        turnStateManager = mock(TurnStateManager.class);
        backgroundReviewService = mock(BackgroundReviewService.class);
        interruptToken = mock(InterruptToken.class);
        turnFinalizer = mock(TurnFinalizer.class);
        steerBuffer = mock(SteerBuffer.class);
        errorClassifier = new ErrorClassifier();

        // Default stubs
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

        when(guardrail.isHalted()).thenReturn(false);

        runtime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    // ── stripThinkBlocksFromString: tag variant tests ──────────────────

    @Test
    @DisplayName("stripThinkBlocksFromString strips ⇋...⇋ blocks")
    void stripThinkTagPair() {
        String input = "Hello ⇋internal reasoning⇋ World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("internal reasoning");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips <thinking>...</thinking> blocks")
    void stripThinkingTagPair() {
        String input = "Hello <thinking>internal reasoning</thinking> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("internal reasoning");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips <reasoning>...</reasoning> blocks")
    void stripReasoningTagPair() {
        String input = "Hello <reasoning>step by step</reasoning> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("step by step");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips <thought>...</thought> blocks")
    void stripThoughtTagPair() {
        String input = "Hello <thought>my thoughts</thought> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("my thoughts");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips <REASONING_SCRATCHPAD>...</REASONING_SCRATCHPAD> blocks")
    void stripReasoningScratchpadTagPair() {
        String input = "Hello <REASONING_SCRATCHPAD>scratch content</REASONING_SCRATCHPAD> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("scratch content");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips <antml:thinking>...</antml:thinking> blocks")
    void stripAntmlThinkingTagPair() {
        String input = "Hello <antml:thinking>anthropic reasoning</antml:thinking> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("anthropic reasoning");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString is case-insensitive")
    void stripCaseInsensitive() {
        String input = "Hello <THINKING>internal</THINKING> World";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("internal");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips unterminated open tag at block boundary")
    void stripUnterminatedOpenTag() {
        String input = "Visible text\n<thinking>unterminated reasoning that runs to end";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Visible text");
        assertThat(result).doesNotContain("unterminated reasoning");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString strips stray orphan tags")
    void stripStrayOrphanTags() {
        String input = "Hello </thinking> World <thinking>";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("<thinking>");
        assertThat(result).doesNotContain("</thinking>");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString handles multiple think blocks in sequence")
    void stripMultipleThinkBlocks() {
        String input = "Start ⇋first⇋ middle ⇋second⇋ End";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).contains("Start");
        assertThat(result).contains("middle");
        assertThat(result).contains("End");
        assertThat(result).doesNotContain("first");
        assertThat(result).doesNotContain("second");
    }

    @Test
    @DisplayName("stripThinkBlocksFromString returns empty for null/empty input")
    void stripNullEmpty() {
        assertThat(ThinkBlockProcessor.stripThinkBlocksFromString(null)).isEmpty();
        assertThat(ThinkBlockProcessor.stripThinkBlocksFromString("")).isEmpty();
    }

    // ── hasContentAfterThinkBlock tests ──────────────────────────────

    @Test
    @DisplayName("hasContentAfterThinkBlock returns true when there is visible text")
    void hasContentAfterThinkBlock_true() {
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock("⇋reasoning⇋ Hello World")).isTrue();
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock("<thinking>reasoning</thinking> visible")).isTrue();
    }

    @Test
    @DisplayName("hasContentAfterThinkBlock returns false when only think blocks")
    void hasContentAfterThinkBlock_false() {
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock("⇋reasoning only⇋")).isFalse();
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock("<thinking>just reasoning</thinking>")).isFalse();
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock("")).isFalse();
        assertThat(ThinkBlockProcessor.hasContentAfterThinkBlock(null)).isFalse();
    }

    // ── hasIncompleteScratchpad tests ─────────────────────────────────

    @Test
    @DisplayName("hasIncompleteScratchpad detects open without close")
    void hasIncompleteScratchpad_true() {
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad(
            "<REASONING_SCRATCHPAD>thinking about the problem")).isTrue();
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad(
            "text <REASONING_SCRATCHPAD>more thinking")).isTrue();
    }

    @Test
    @DisplayName("hasIncompleteScratchpad returns false for complete or absent")
    void hasIncompleteScratchpad_false() {
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad(
            "<REASONING_SCRATCHPAD>complete</REASONING_SCRATCHPAD>")).isFalse();
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad("no scratchpad here")).isFalse();
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad(null)).isFalse();
        assertThat(ThinkBlockProcessor.hasIncompleteScratchpad("")).isFalse();
    }

    // ── isThinkingBudgetExhausted tests ──────────────────────────────

    @Test
    @DisplayName("isThinkingBudgetExhausted returns true for think-only response without tool calls")
    void isThinkingBudgetExhausted_true() {
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(
            ChatResponse.text("⇋all reasoning no visible text⇋"))).isTrue();
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(
            ChatResponse.text("<thinking>only thinking</thinking>"))).isTrue();
    }

    @Test
    @DisplayName("isThinkingBudgetExhausted returns false for response with visible text")
    void isThinkingBudgetExhausted_false_hasVisible() {
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(
            ChatResponse.text("⇋reasoning⇋ visible answer"))).isFalse();
    }

    @Test
    @DisplayName("isThinkingBudgetExhausted returns false for response with tool calls")
    void isThinkingBudgetExhausted_false_hasToolCalls() {
        var response = ChatResponse.toolCalls(List.of(
            new ToolCall("c1", "weather", "{\"city\":\"London\"}")));
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(response)).isFalse();
    }

    @Test
    @DisplayName("isThinkingBudgetExhausted returns false for response without think tags")
    void isThinkingBudgetExhausted_false_noThinkTags() {
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(
            ChatResponse.text("just a normal response"))).isFalse();
    }

    @Test
    @DisplayName("isThinkingBudgetExhausted returns false for null response")
    void isThinkingBudgetExhausted_false_null() {
        assertThat(ThinkBlockProcessor.isThinkingBudgetExhausted(null)).isFalse();
    }

    // ── Integration tests with the turn loop ─────────────────────────

    @Test
    @DisplayName("Thinking-only response triggers prefill continuation, then model produces visible text")
    void thinkingOnlyResponse_triggersPrefillContinuation() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    // First call: thinking-only response
                    return ChatResponse.text("⇋Let me think about this...⇋");
                }
                // Second call: model sees its reasoning and produces visible text
                return ChatResponse.text("The answer is 42.");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What is the answer?");

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isEqualTo(2); // prefill continuation used 2nd call
        // Final message should be the stripped visible text
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("The answer is 42.");
    }

    @Test
    @DisplayName("Thinking-only response with <thinking> tags triggers prefill continuation")
    void thinkingOnlyResponse_withThinkingTags_triggersPrefill() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    return ChatResponse.text("<thinking>Reasoning about the problem</thinking>");
                }
                return ChatResponse.text("Visible response.");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isEqualTo(2);
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("Visible response.");
    }

    @Test
    @DisplayName("Thinking-only prefill exhausted after 2 retries returns Thinking Budget Exhausted message")
    void thinkingOnlyPrefillExhausted_returnsBudgetExhaustedMessage() {
        // Model always returns thinking-only, never produces visible text
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("⇋thinking forever and never finishing⇋"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        // Should have made 3 calls: 1 initial + 2 prefill retries
        // But the 3rd thinking-only response (prefillRetries == 2, so >= 2, no more prefill)
        // falls through to isThinkingBudgetExhausted check which returns the exhaust message
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).contains("Thinking Budget Exhausted");
    }

    @Test
    @DisplayName("Thinking budget exhaustion returns user-friendly message (no retry)")
    void thinkingBudgetExhausted_returnsTerminalMessage() {
        // Content with think tags but no visible text, and the isThinkingBudgetExhausted
        // should trigger before prefill continuation kicks in.
        // After 2 prefill retries, the 3rd call still returns think-only → exhausted
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("<thinking>deep reasoning with no output</thinking>"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Complex question");

        assertThat(result.completed()).isTrue();
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).contains("Thinking Budget Exhausted");
        assertThat(lastMsg.content()).contains("max_tokens");
    }

    @Test
    @DisplayName("Incomplete <REASONING_SCRATCHPAD> triggers retry, then model completes")
    void incompleteScratchpad_triggersRetry_thenCompletes() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    // Incomplete scratchpad (open but never closed)
                    return ChatResponse.text("<REASONING_SCRATCHPAD>Let me think about this...");
                }
                // Second call: model produces complete response
                return ChatResponse.text("The answer is clear.");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What do you think?");

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isEqualTo(2); // retried once
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("The answer is clear.");
    }

    @Test
    @DisplayName("Incomplete scratchpad after 2 retries returns error")
    void incompleteScratchpad_maxRetriesExhausted_returnsError() {
        // Model always returns incomplete scratchpad
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("<REASONING_SCRATCHPAD>still thinking..."));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Incomplete REASONING_SCRATCHPAD");
    }

    @Test
    @DisplayName("Think blocks are stripped from final output before returning to user")
    void thinkBlocksStrippedFromFinalOutput() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("⇋internal reasoning⇋ Here is the visible answer."));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("Here is the visible answer.");
        assertThat(lastMsg.content()).doesNotContain("internal reasoning");
    }

    @Test
    @DisplayName("Think blocks with <reasoning> tags are stripped from final output")
    void reasoningTagsStrippedFromFinalOutput() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("<reasoning>step by step analysis</reasoning> The result is 100."));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Calculate");

        assertThat(result.completed()).isTrue();
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("The result is 100.");
        assertThat(lastMsg.content()).doesNotContain("step by step analysis");
    }

    @Test
    @DisplayName("Response with think blocks and tool calls proceeds to tool execution")
    void thinkBlocksWithToolCalls_proceedsToTools() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    // Response with think blocks AND tool calls
                    return new ChatResponse(
                        "⇋Let me check the weather⇋",
                        List.of(toolCall));
                }
                return ChatResponse.text("The weather in London is sunny.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Prefill continuation resets counter when tool calls follow")
    void prefillContinuation_resetsOnToolCalls() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    // Thinking-only → prefill
                    return ChatResponse.text("⇋thinking⇋");
                }
                if (n == 2) {
                    // Now model produces tool calls (not thinking-only)
                    return new ChatResponse("⇋more thinking⇋", List.of(toolCall));
                }
                if (n == 3) {
                    // After tools, thinking-only again → prefill should work again
                    return ChatResponse.text("⇋thinking after tools⇋");
                }
                return ChatResponse.text("Final answer.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isEqualTo(4); // prefill, tools, prefill-after-tools, final
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.content()).isEqualTo("Final answer.");
    }
}