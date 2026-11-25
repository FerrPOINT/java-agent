package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import java.util.UUID;

/** Approval policy only. Loop state belongs to ToolCallGuardrail. */
public interface ToolApprovalPolicy {
    McpToolTrustService.ToolTrustMetadata mcpAdmission(ToolCall call);
    boolean requiresApproval(ToolCall call, McpToolTrustService.ToolTrustMetadata admission);
    ApprovalQueue.PendingApproval requestApproval(UUID sessionId, ToolCall call);
}
