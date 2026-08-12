package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record StoreMemoryRequest(
    String userId,
    @NotBlank String fact,
    String category,
    String target
) {}