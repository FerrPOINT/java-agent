package com.azhukov.agent.api.dto;

/**
 * DTO for credits/usage summary returned by {@code GET /api/v1/agent/credits}.
 */
public record CreditsDto(
    double totalCost,
    int totalTokens,
    int totalMessages
) {
}