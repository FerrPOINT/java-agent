package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record VoiceModeRequest(
    @NotBlank String sessionId,
    String enabled
) {}