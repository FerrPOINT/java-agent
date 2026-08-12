package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PersonalityRequest(
    @NotBlank String sessionId,
    String personality
) {}