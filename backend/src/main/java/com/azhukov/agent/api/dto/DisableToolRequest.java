package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record DisableToolRequest(
    @NotBlank String sessionId,
    @NotBlank String toolName
) {}