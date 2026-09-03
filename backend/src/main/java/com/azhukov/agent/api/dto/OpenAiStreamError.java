package com.azhukov.agent.api.dto;

public record OpenAiStreamError(
    String type,
    String message
) {}
