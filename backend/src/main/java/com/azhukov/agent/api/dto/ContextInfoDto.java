package com.azhukov.agent.api.dto;

import java.util.List;
import java.util.UUID;

public record ContextInfoDto(
    UUID sessionId,
    int messageCount,
    int tokenEstimate,
    List<String> toolsUsed,
    String goal,
    Boolean goalPaused,
    String subgoals
) {
    /** Backward-compatible constructor for callers that don't supply goal info. */
    public ContextInfoDto(UUID sessionId, int messageCount, int tokenEstimate, List<String> toolsUsed) {
        this(sessionId, messageCount, tokenEstimate, toolsUsed, null, null, null);
    }
}