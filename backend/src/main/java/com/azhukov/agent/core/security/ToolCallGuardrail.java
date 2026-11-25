package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolResult;
import java.util.UUID;

public interface ToolCallGuardrail {
    GuardrailDecision beforeCall(String toolName, String arguments);
    GuardrailDecision afterCall(String toolName, String arguments, ToolResult result, boolean failed);
    String resultReferenceStub(String toolName, String arguments, String result, boolean failed);
    void reset(UUID sessionId);
    void removeSession(UUID sessionId);
    boolean isHalted(UUID sessionId);
    String haltMessage(UUID sessionId);
}
