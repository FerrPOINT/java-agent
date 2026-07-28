package com.azhukov.agent.api.dto;

import java.util.List;
import java.util.UUID;

public record ContextInfoDto(
    UUID sessionId,
    int messageCount,
    int tokenEstimate,
    List<String> toolsUsed
) {}