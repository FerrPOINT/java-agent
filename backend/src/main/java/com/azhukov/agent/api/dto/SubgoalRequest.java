package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubgoalRequest(
    @NotBlank String sessionId,
    String subgoal,
    String append
) {}