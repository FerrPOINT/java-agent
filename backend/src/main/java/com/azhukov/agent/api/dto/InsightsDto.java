package com.azhukov.agent.api.dto;

import java.util.Map;

public record InsightsDto(int totalTokens, int totalMessages, Map<String, Integer> byModel) {}