package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.budget.IterationBudget.TurnSnapshot;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAgentRuntime implements AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final PromptBuilder promptBuilder;
    private final ContextEngine contextEngine;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final IterationBudget iterationBudget;
    private final MessageSanitizer messageSanitizer;
    private final ContextReferenceService contextReferenceService;
    private final AgentProperties properties;
    private final UserInputSanitizer inputSanitizer;
    private final ToolCallGuardrail guardrail;
    private final TurnStateManager turnStateManager;
    private final BackgroundReviewService backgroundReviewService;
    private final InterruptToken interruptToken;
    private final TurnFinalizer turnFinalizer;
    private final SteerBuffer steerBuffer;
    private final ErrorClassifier errorClassifier;

    @Override
    public ChatResponse run(List<Message> messages, List<ToolDefinition> tools) {
        List<Message> sanitized = messageSanitizer.sanitize(messages);
        List<Message> context = contextEngine.prepareContext(
            Session.create("openai-user", "openai-compatible", ""), sanitized);
        return modelClient.complete(context, tools);
    }

    @Override
    public TurnResult runTurn(Session session, String userInput, List<String> references) {
        UUID sessionIdUuid = session.id();
        String sessionId = sessionIdUuid.toString();
        guardrail.reset();
        turnStateManager.clear(sessionIdUuid);
        TurnSnapshot budget = iterationBudget.startTurn(sessionIdUuid);
        String safeInput = inputSanitizer.sanitize(userInput);
        TurnState turnState = turnStateManager.getOrStart(sessionIdUuid, 1);
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));
        if (references != null && !references.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(safeInput).append("\n\n").append("--- References ---");
            for (var ref : contextReferenceService.resolve(references)) {
                contextReferenceService.loadContent(ref).ifPresent(content ->
                    sb.append("\n\n").append("[").append(ref.displayName()).append("]\n").append(content));
            }
            turnMessages.add(Message.user(sb.toString()));
        } else {
            turnMessages.add(Message.user(safeInput));
        }

        List<ToolDefinition> tools = toolRegistry.getDefinitions(new HashSet<>(properties.getSkills().getDefaultToolsets()));
        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;

        for (int i = 0; i < maxTurns; i++) {
            if (guardrail.isHalted()) {
                turnMessages.add(Message.assistant("Turn halted by guardrails.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false);
                }
                return new TurnResult(turnMessages, true, null);
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls, {} tool executions",
                    session.id(), budget.modelCalls(), budget.toolExecutions());
                turnMessages.add(Message.assistant("Iteration budget exhausted. Stopping to avoid runaway loop.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false);
                }
                return new TurnResult(turnMessages, true, null);
            }

            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            ChatResponse response;
            try {
                long callStart = System.currentTimeMillis();
                response = callModelWithRetry(context, tools, session);
                int duration = (int) (System.currentTimeMillis() - callStart);
                int estimatedInput = estimateTokens(context);
                int estimatedOutput = estimateResponseTokens(response);
                budget = iterationBudget.recordModelCall(budget, estimatedInput, estimatedOutput);
                turnState.recordModelCall();
                log.debug("Turn {} model returned in {} ms: toolCalls={}, content length={}",
                    i, duration, response.toolCalls() != null ? response.toolCalls().size() : 0,
                    response.content() != null ? response.content().length() : 0);
            } catch (Exception e) {
                log.error("Model call failed after retries", e);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false);
                }
                return TurnResult.error("Model call failed: " + e.getMessage());
            }

            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                log.debug("Turn {} completed without tool calls", i);
                triggerBackgroundReview(session, turnMessages);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, true);
                }
                return new TurnResult(turnMessages, true, null);
            }

            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            int currentTurnIndex = turnIndex;
            List<Message> toolResults = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;
                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);
                log.debug("Tool {} executed in {} ms: success={}, content length={}, error={}",
                    call.name(), duration, result.success(),
                    result.content() != null ? result.content().length() : 0, result.error());
                toolResults.add(Message.toolResult(call.id(), formatResult(result), currentTurnIndex));
            }
            // Inject pending steer note into the last tool result
            String steerText = steerBuffer.consume(session.id());
            if (steerText != null && !toolResults.isEmpty()) {
                Message lastToolResult = toolResults.get(toolResults.size() - 1);
                String enhancedContent = lastToolResult.content() + "\n\n[STEER NOTE] " + steerText;
                toolResults.set(toolResults.size() - 1,
                    Message.toolResult(lastToolResult.toolCallId(), enhancedContent, currentTurnIndex));
                log.info("Injected steer note for session {}", session.id());
            }
            turnMessages.addAll(toolResults);
            turnIndex++;
        }

        if (turnFinalizer != null) {
            turnFinalizer.finalize(session.id(), turnMessages, false);
        }
        return TurnResult.error("Reached max turns without completion");
    }

    /**
     * Calls modelClient.complete() with retry logic based on ErrorClassifier.
     * RETRYABLE errors use jittered backoff (500ms * 2^attempt + 0-250ms, cap 5s).
     * RATE_LIMIT errors use longer backoff (2s * 2^attempt, cap 30s).
     * PERMANENT/BILLING/CONTEXT_OVERFLOW/CONTENT_POLICY errors fail immediately.
     */
    private ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools, Session session) {
        int retryAttempts = properties.getError().getRetryAttempts();
        Exception lastException = null;
        int totalAttempts = 0;

        for (int attempt = 0; attempt <= retryAttempts; attempt++) {
            totalAttempts++;
            try {
                return modelClient.complete(context, tools);
            } catch (Exception e) {
                lastException = e;
                if (attempt >= retryAttempts) {
                    break;
                }
                ErrorClassifier.ErrorType errorType = errorClassifier.classify(e);
                if (errorType == ErrorClassifier.ErrorType.PERMANENT
                    || errorType == ErrorClassifier.ErrorType.BILLING
                    || errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW
                    || errorType == ErrorClassifier.ErrorType.CONTENT_POLICY) {
                    log.warn("Model call failed with {} error, not retrying: {}", errorType, e.getMessage());
                    break;
                }
                // Calculate backoff delay
                long delayMs;
                if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                    delayMs = Math.min(2000L * (1L << attempt), 30_000L);
                } else {
                    long base = 500L * (1L << attempt);
                    long jitter = ThreadLocalRandom.current().nextLong(0, 250);
                    delayMs = Math.min(base + jitter, 5_000L);
                }
                log.warn("Model call failed (attempt {}/{}), classified as {}, retrying in {} ms: {}",
                    attempt + 1, retryAttempts + 1, errorType, delayMs, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException("Model call failed after " + totalAttempts + " attempt(s): "
            + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private void triggerBackgroundReview(Session session, List<Message> turnMessages) {
        try {
            if (backgroundReviewService != null) {
                backgroundReviewService.clearFlag(session.id());
                backgroundReviewService.reviewTurn(session.id(), turnMessages);
            }
        } catch (Exception e) {
            log.debug("Background review trigger failed: {}", e.getMessage());
        }
    }

    private String formatResult(ToolResult result) {
        if (result.success()) {
            return result.content();
        }
        return "Error: " + result.error();
    }

    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message m : messages) {
            chars += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    chars += tc.arguments() != null ? tc.arguments().length() : 0;
                    chars += tc.name() != null ? tc.name().length() : 0;
                }
            }
        }
        return chars / 4 + 1;
    }

    private int estimateResponseTokens(ChatResponse response) {
        int chars = response.content() != null ? response.content().length() : 0;
        if (response.toolCalls() != null) {
            for (ToolCall tc : response.toolCalls()) {
                chars += tc.arguments() != null ? tc.arguments().length() : 0;
                chars += tc.name() != null ? tc.name().length() : 0;
            }
        }
        return chars / 4 + 1;
    }
}