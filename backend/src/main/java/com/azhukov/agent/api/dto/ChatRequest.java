package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ChatRequest(
    UUID sessionId,
    @NotBlank String message,
    Integer delegationDepth,
    Long timeoutMs,
    String reasoningEffort,
    Boolean fastMode,
    Boolean voiceMode,
    String personality,
    java.util.List<String> enabledTools,
    java.util.List<String> disabledTools,
    String queuedPrompt,
    String subgoal,
    String cdpUrl,
    String goal
) {
    // Backward-compatible compact constructor used by existing tests
    public ChatRequest(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs) {
        this(sessionId, message, delegationDepth, timeoutMs, null, null, null, null, null, null, null, null, null, null);
    }

    // Constructor with sessionId + message + all runtime flags except goal (backward compat)
    public ChatRequest(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs,
                       String reasoningEffort, Boolean fastMode, Boolean voiceMode, String personality,
                       java.util.List<String> enabledTools, java.util.List<String> disabledTools,
                       String queuedPrompt, String subgoal, String cdpUrl) {
        this(sessionId, message, delegationDepth, timeoutMs, reasoningEffort, fastMode, voiceMode, personality,
            enabledTools, disabledTools, queuedPrompt, subgoal, cdpUrl, null);
    }
}
