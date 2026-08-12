package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record BrowserRequest(
    @NotBlank String sessionId,
    String cdpUrl
) {}