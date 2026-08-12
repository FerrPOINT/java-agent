package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record QueueRequest(
    @NotBlank String sessionId,
    String queued
) {}