package com.azhukov.agent.core.agent;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.springframework.stereotype.Component;

/**
 * Shared CLI state application logic used by both the streaming
 * ({@code AgentStreamingService}) and sync ({@code AgentRuntimeService}) paths.
 *
 * <p>Reads CLI state values (reasoning effort, personality, queued prompt, goal,
 * subgoals, subgoal) from the session entity and merges them into a new
 * {@link ChatRequest} with a suitably prefixed user message.
 */
@Component
public class CliStateApplier {

    /** True when the last applyCliState merged a queued prompt that must be cleared. */
    private final java.util.concurrent.atomic.AtomicBoolean consumedQueuedPrompt =
        new java.util.concurrent.atomic.AtomicBoolean(false);


    /**
     * Apply CLI runtime settings from the session entity to the request.
     *
     * <p>If the session is {@code null} the request is returned unchanged.
     * The merged message is built from the session's goal, subgoals, subgoal,
     * and queued-prompt CLI state values, prepended to the original user message.
     *
     * @param request the incoming chat request
     * @param session the session entity (may be {@code null})
     * @return a new {@link ChatRequest} with merged message and resolved fields
     */
    public ChatRequest applyCliState(ChatRequest request, SessionEntity session) {
        if (session == null) {
            return request;
        }
        String reasoningEffort = request.reasoningEffort() != null ? request.reasoningEffort() : session.getCliStateValue("reasoningEffort");
        String personality = request.personality() != null ? request.personality() : session.getCliStateValue("personality");
        String queuedPrompt = request.queuedPrompt() != null ? request.queuedPrompt() : session.getCliStateValue("queuedPrompt");
        String subgoal = request.subgoal() != null ? request.subgoal() : session.getSubgoal();
        String goal = request.goal() != null ? request.goal() : session.getCliStateValue("goal");
        if (goal == null || "true".equals(session.getCliStateValue("goalPaused"))) {
            goal = null;
        }
        String subgoals = session.getCliStateValue("subgoals");

        String finalMessage = buildMergedMessage(request.message(), queuedPrompt, goal, subgoals, subgoal);
        // Hermes /queue semantics: the queued prompt applies to the NEXT turn only.
        // Mark it consumed so callers can clear the persisted value inside a write tx.
        this.consumedQueuedPrompt.set(queuedPrompt != null && !queuedPrompt.isBlank());
        return new ChatRequest(
            request.sessionId(),
            finalMessage,
            request.delegationDepth(),
            request.timeoutMs(),
            request.model(),
            request.provider(),
            request.baseUrl(),
            request.apiKey(),
            reasoningEffort,
            request.fastMode(),
            request.voiceMode(),
            personality,
            request.enabledTools(),
            request.disabledTools(),
            null, // consumed
            null,
            request.maxCompletionTokens(),
            request.systemPromptOverride(),
            request.cdpUrl(),
            null, // goal
            request.userId(),
            request.username(),
            request.firstName(),
            request.languageCode(),
            request.chatType(),
            request.serviceTier(),
            request.yoloMode(),
            request.verboseMode(),
            request.footerEnabled()
        );
    }

    /**
     * Build the merged user message by prepending goal/subgoal/queued-prompt
     * context blocks to the original user message.
     */
    private String buildMergedMessage(String userMessage, String queuedPrompt, String goal, String subgoals, String subgoal) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("[Standing Goal]\n").append(goal).append("\n\n");
        }
        if (subgoals != null && !subgoals.isBlank()) {
            sb.append("[Subgoals]\n").append(subgoals).append("\n\n");
        }
        if (subgoal != null && !subgoal.isBlank()) {
            sb.append("[Goal/Subgoal]\n").append(subgoal).append("\n\n");
        }
        if (queuedPrompt != null && !queuedPrompt.isBlank()) {
            sb.append("[Queued context]\n").append(queuedPrompt).append("\n\n");
        }
        sb.append(userMessage);
        return sb.toString();
    }

    /** Whether the last {@link #applyCliState} consumed a queued prompt (callers clear it in a write tx). */
    public boolean consumeQueuedPromptFlag() {
        return consumedQueuedPrompt.getAndSet(false);
    }
}
