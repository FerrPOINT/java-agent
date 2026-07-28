package com.azhukov.agent.core.agent;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);

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

    public DefaultAgentRuntime(ModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutionService toolExecutionService,
                               PromptBuilder promptBuilder, ContextEngine contextEngine,
                               MemoryProvider memoryProvider, SkillManager skillManager,
                               IterationBudget iterationBudget,
                               MessageSanitizer messageSanitizer,
                               ContextReferenceService contextReferenceService,
                               AgentProperties properties,
                               UserInputSanitizer inputSanitizer,
                               ToolCallGuardrail guardrail,
                               TurnStateManager turnStateManager,
                               BackgroundReviewService backgroundReviewService) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.promptBuilder = promptBuilder;
        this.contextEngine = contextEngine;
        this.memoryProvider = memoryProvider;
        this.skillManager = skillManager;
        this.iterationBudget = iterationBudget;
        this.messageSanitizer = messageSanitizer;
        this.contextReferenceService = contextReferenceService;
        this.properties = properties;
        this.inputSanitizer = inputSanitizer;
        this.guardrail = guardrail;
        this.turnStateManager = turnStateManager;
        this.backgroundReviewService = backgroundReviewService;
    }

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
                return new TurnResult(turnMessages, true, null);
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls, {} tool executions",
                    session.id(), budget.modelCalls(), budget.toolExecutions());
                turnMessages.add(Message.assistant("Iteration budget exhausted. Stopping to avoid runaway loop.", turnIndex));
                return new TurnResult(turnMessages, true, null);
            }

            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            ChatResponse response;
            try {
                long callStart = System.currentTimeMillis();
                response = modelClient.complete(context, tools);
                int duration = (int) (System.currentTimeMillis() - callStart);
                int estimatedInput = estimateTokens(context);
                int estimatedOutput = estimateResponseTokens(response);
                budget = iterationBudget.recordModelCall(budget, estimatedInput, estimatedOutput);
                turnState.recordModelCall();
                log.debug("Turn {} model returned in {} ms: toolCalls={}, content length={}",
                    i, duration, response.toolCalls() != null ? response.toolCalls().size() : 0,
                    response.content() != null ? response.content().length() : 0);
            } catch (Exception e) {
                log.error("Model call failed", e);
                return TurnResult.error("Model call failed: " + e.getMessage());
            }

            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                log.debug("Turn {} completed without tool calls", i);
                triggerBackgroundReview(session, turnMessages);
                return new TurnResult(turnMessages, true, null);
            }

            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            int currentTurnIndex = turnIndex;
            List<Message> toolResults = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;
                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);
                log.debug("Tool {} executed in {} ms: success={}, content length={}, error={}",
                    call.name(), duration, result.success(),
                    result.content() != null ? result.content().length() : 0, result.error());
                toolResults.add(Message.toolResult(call.id(), formatResult(result), currentTurnIndex));
            }
            turnMessages.addAll(toolResults);
            turnIndex++;
        }

        return TurnResult.error("Reached max turns without completion");
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
