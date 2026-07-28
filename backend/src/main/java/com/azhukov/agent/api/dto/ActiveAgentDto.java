package com.azhukov.agent.api.dto;

public record ActiveAgentDto(String sessionId, String status, long startTime, String message) {}