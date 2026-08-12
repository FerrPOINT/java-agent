package com.azhukov.agent.security;

import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;

import java.util.UUID;

public interface ToolCallGuardrail {
    default GuardrailDecision beforeCall(String toolName, String arguments, TurnState state) {
        GuardrailDecision base = beforeCall(toolName, arguments);
        if (!base.isAllow() || state == null) return base;
        if (state.repeatCountFor(new ToolCall("", toolName, arguments)) >= 2) {
            return GuardrailDecision.warn(toolName, "repeated_call", "Identical call repeated within turn");
        }
        return base;
    }
    default GuardrailDecision afterCall(String toolName, String arguments, ToolResult result, boolean failed, TurnState state) {
        return afterCall(toolName, arguments, result, failed);
    }
    GuardrailDecision beforeCall(String toolName, String arguments);
    GuardrailDecision afterCall(String toolName, String arguments, ToolResult result, boolean failed);
    default void reset() {}
    default void reset(UUID sessionId) { reset(); }
    default boolean isHalted() { return false; }
    default boolean isHalted(UUID sessionId) { return isHalted(); }
}