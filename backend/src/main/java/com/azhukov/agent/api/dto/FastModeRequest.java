package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FastModeRequest(
    @NotBlank String sessionId,
    String enabled
) {}