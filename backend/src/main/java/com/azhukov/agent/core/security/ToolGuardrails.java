package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;

public interface ToolGuardrails {

    boolean isToolAllowed(String toolName);

    boolean requiresApproval(ToolCall call);
}
