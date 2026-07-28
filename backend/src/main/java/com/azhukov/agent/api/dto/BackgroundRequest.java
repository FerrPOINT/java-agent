package com.azhukov.agent.api.dto;

public record BackgroundRequest(
    String prompt,
    String sessionId
) {}