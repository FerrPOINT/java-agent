package com.azhukov.agent.api.dto;

import java.util.Map;

public record AgentConfigDto(
    String name,
    String model,
    String provider,
    String baseUrl,
    int maxTurns,
    int maxModelCallsPerTurn,
    int maxToolExecutionsPerTurn,
    int maxTokens,
    double temperature,
    int timeoutSeconds,
    String defaultSystemPrompt,
    String reasoningConfig,
    int memoryNudgeInterval,
    int skillCreationNudgeInterval,
    boolean backgroundReviewEnabled,
    int backgroundReviewDelayMs,
    int backgroundReviewMaxTurns,
    int backgroundReviewMaxInputTokens,
    Map<String, Boolean> features
) {}
