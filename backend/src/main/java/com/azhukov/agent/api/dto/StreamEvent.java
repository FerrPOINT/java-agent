package com.azhukov.agent.api.dto;

import com.azhukov.agent.core.model.ToolCall;

import java.util.List;

public record StreamEvent(
    String type,
    String token,
    List<ToolCall> toolCalls,
    String error
) {}
