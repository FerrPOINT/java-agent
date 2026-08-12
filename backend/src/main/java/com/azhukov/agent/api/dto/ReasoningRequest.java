package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReasoningRequest(
    @NotBlank String sessionId,
    String effort
) {}