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
    String goal,
    String userId,
    String username,
    String firstName,
    String languageCode
) {
    // Static factory — compact 4-arg form for tests and simple calls
    public static ChatRequest simple(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs) {
        return new ChatRequest(sessionId, message, delegationDepth, timeoutMs,
            (String) null, (Boolean) null, (Boolean) null, (String) null,
            (java.util.List<String>) null, (java.util.List<String>) null,
            (String) null, (String) null, (String) null,  // queuedPrompt, subgoal, cdpUrl
            (String) null, (String) null, (String) null, (String) null, (String) null); // goal..languageCode
    }

    // Static factory — 13-arg form (all runtime flags except goal/userId)
    public static ChatRequest withFlags(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs,
                                 String reasoningEffort, Boolean fastMode, Boolean voiceMode, String personality,
                                 java.util.List<String> enabledTools, java.util.List<String> disabledTools,
                                 String queuedPrompt, String subgoal, String cdpUrl) {
        return new ChatRequest(sessionId, message, delegationDepth, timeoutMs, reasoningEffort, fastMode,
            voiceMode, personality, enabledTools, disabledTools, queuedPrompt, subgoal, cdpUrl,
            (String) null, (String) null, (String) null, (String) null, (String) null); // goal..languageCode
    }
}