package com.azhukov.agent.api.dto;

@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenAiStreamError(
    String type,
    String message
) {}
