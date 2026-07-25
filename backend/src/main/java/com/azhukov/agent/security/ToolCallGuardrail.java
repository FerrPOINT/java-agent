package com.azhukov.agent.security;

import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;

public interface ToolCallGuardrail {
    GuardrailDecision beforeCall(String toolName, String args);
    GuardrailDecision afterCall(String toolName, String args, ToolResult result, boolean failed);
    boolean isHalted();
    void reset();
}
