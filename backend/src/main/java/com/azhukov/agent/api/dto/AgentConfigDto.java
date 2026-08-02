package com.azhukov.agent.api.dto;

import java.util.Map;

public record AgentConfigDto(
    String name,
    String model,
    String provider,
    String baseUrl,
    int maxTurns,
    int maxModelCallsPerTurn,
    int maxTokens,
    double temperature,
    int timeoutSeconds,
    String defaultSystemPrompt,
    String reasoningConfig,
    Map<String, Boolean> features
) {}
