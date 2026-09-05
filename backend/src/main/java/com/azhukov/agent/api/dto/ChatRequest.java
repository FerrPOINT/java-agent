package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ChatRequest(
    UUID sessionId,
    @NotBlank String message,
    Integer delegationDepth,
    Long timeoutMs,
    String model,
    String provider,
    String baseUrl,
    String apiKey,
    String reasoningEffort,
    Boolean fastMode,
    Boolean voiceMode,
    String personality,
    java.util.List<String> enabledTools,
    java.util.List<String> disabledTools,
    String queuedPrompt,
    String subgoal,
    Integer maxCompletionTokens,
    String systemPromptOverride,
    String cdpUrl,
    String goal,
    String userId,
    String username,
    String firstName,
    String languageCode,
    String chatType,
    String serviceTier,
    Boolean yoloMode,
    Boolean verboseMode,
    Boolean footerEnabled
) {
    public ChatRequest(UUID sessionId,
                       String message,
                       Integer delegationDepth,
                       Long timeoutMs,
                       String model,
                       String provider,
                       String baseUrl,
                       String apiKey,
                       String reasoningEffort,
                       Boolean fastMode,
                       Boolean voiceMode,
                       String personality,
                       java.util.List<String> enabledTools,
                       java.util.List<String> disabledTools,
                       String queuedPrompt,
                       String subgoal,
                       Integer maxCompletionTokens,
                       String systemPromptOverride,
                       String cdpUrl,
                       String goal,
                       String userId,
                       String username,
                       String firstName,
                       String languageCode,
                       String chatType) {
        this(sessionId, message, delegationDepth, timeoutMs,
            model, provider, baseUrl, apiKey, reasoningEffort, fastMode, voiceMode,
            personality, enabledTools, disabledTools, queuedPrompt, subgoal,
            maxCompletionTokens, systemPromptOverride, cdpUrl, goal, userId, username,
            firstName, languageCode, chatType, null, null, null, null);
    }

    public ChatRequest(UUID sessionId,
                       String message,
                       Integer delegationDepth,
                       Long timeoutMs,
                       String model,
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
                       String languageCode,
                       String chatType) {
        this(sessionId, message, delegationDepth, timeoutMs,
            model, null, null, null, reasoningEffort, fastMode, voiceMode,
            personality, enabledTools, disabledTools, queuedPrompt, subgoal,
            null, null, cdpUrl, goal, userId, username, firstName, languageCode, chatType);
    }

    // Static factory — compact 4-arg form for tests and simple calls
    public static ChatRequest simple(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs) {
        return new ChatRequest(sessionId, message, delegationDepth, timeoutMs,
            (String) null, (String) null, (Boolean) null, (Boolean) null, (String) null,
            (java.util.List<String>) null, (java.util.List<String>) null,
            (String) null, (String) null, (String) null,  // model, queuedPrompt, subgoal, cdpUrl
            (String) null, (String) null, (String) null, (String) null, (String) null, (String) null); // goal..chatType
    }

    // Static factory — 13-arg form (all runtime flags except goal/userId/chatType)
    public static ChatRequest withFlags(UUID sessionId, String message, Integer delegationDepth, Long timeoutMs,
                                 String reasoningEffort, Boolean fastMode, Boolean voiceMode, String personality,
                                 java.util.List<String> enabledTools, java.util.List<String> disabledTools,
                                 String queuedPrompt, String subgoal, String cdpUrl) {
        return new ChatRequest(sessionId, message, delegationDepth, timeoutMs,
            null, reasoningEffort, fastMode,
            voiceMode, personality, enabledTools, disabledTools, queuedPrompt, subgoal, cdpUrl,
            (String) null, (String) null, (String) null, (String) null, (String) null, (String) null);
    }

    @Override
    public String toString() {
        return "ChatRequest[" +
            "sessionId=" + sessionId +
            ", message=" + message +
            ", delegationDepth=" + delegationDepth +
            ", timeoutMs=" + timeoutMs +
            ", model=" + model +
            ", provider=" + provider +
            ", baseUrl=" + baseUrl +
            ", apiKey=" + (apiKey != null && !apiKey.isBlank() ? "<redacted>" : apiKey) +
            ", reasoningEffort=" + reasoningEffort +
            ", fastMode=" + fastMode +
            ", voiceMode=" + voiceMode +
            ", personality=" + personality +
            ", enabledTools=" + enabledTools +
            ", disabledTools=" + disabledTools +
            ", queuedPrompt=" + queuedPrompt +
            ", subgoal=" + subgoal +
            ", maxCompletionTokens=" + maxCompletionTokens +
            ", systemPromptOverride=" + systemPromptOverride +
            ", cdpUrl=" + cdpUrl +
            ", goal=" + goal +
            ", userId=" + userId +
            ", username=" + username +
            ", firstName=" + firstName +
            ", languageCode=" + languageCode +
            ", chatType=" + chatType +
            ", serviceTier=" + serviceTier +
            ']';
    }
}
