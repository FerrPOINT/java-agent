package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EnableToolRequest(
    @NotBlank String sessionId,
    @NotBlank String toolName
) {}