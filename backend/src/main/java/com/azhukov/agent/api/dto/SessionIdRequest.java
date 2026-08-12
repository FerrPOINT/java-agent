package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SessionIdRequest(
    @NotBlank String sessionId
) {}