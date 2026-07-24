package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record VisionRequest(
    @NotBlank String url,
    @NotBlank String prompt,
    Integer waitSeconds
) {}
