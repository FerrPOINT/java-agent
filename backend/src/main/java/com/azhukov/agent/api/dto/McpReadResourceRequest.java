package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record McpReadResourceRequest(
    @NotBlank String uri
) {}