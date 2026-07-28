package com.azhukov.agent.api.dto;

import java.util.UUID;

public record UsageDto(
    UUID sessionId,
    int messageCount,
    int tokenEstimate
) {}