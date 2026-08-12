package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GoalRequest(
    @NotBlank String sessionId,
    @NotBlank String goal
) {}