package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.mcp.McpTool;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

@RequiredArgsConstructor
public class DefaultToolApprovalPolicy implements ToolApprovalPolicy {
    private final AgentProperties properties;
    private final ApprovalQueue approvalQueue;
    @Autowired(required = false)
    private McpToolTrustService mcpToolTrustService;

    @Override
    public McpToolTrustService.ToolTrustMetadata mcpAdmission(ToolCall call) {
        Objects.requireNonNull(call, "Tool call is required");
        if ("mcp_tool".equals(call.name())) {
            if (mcpToolTrustService == null) throw new IllegalStateException("MCP trust service is unavailable");
            var args = ToolHandler.parseJson(call.arguments(), McpTool.McpArgs.class);
            if (args.serverName() == null || args.toolName() == null) {
                throw new IllegalArgumentException("MCP server and tool are required");
            }
            var admission = mcpToolTrustService.metadata(args.serverName(), args.toolName());
            if (admission == null) throw new IllegalArgumentException("MCP tool is not registered");
            return admission;
        }
        return mcpToolTrustService == null ? null : mcpToolTrustService.metadata(call.name());
    }

    @Override
    public boolean requiresApproval(ToolCall call, McpToolTrustService.ToolTrustMetadata admission) {
        Objects.requireNonNull(call, "Tool call is required");
        if (!properties.getSecurity().isApprovalsEnabled()) return false;
        if (admission != null && admission.requiresApproval()) return true;
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }

    public void setMcpToolTrustService(McpToolTrustService mcpToolTrustService) {
        this.mcpToolTrustService = Objects.requireNonNull(mcpToolTrustService);
    }

    @Override
    public ApprovalQueue.PendingApproval requestApproval(UUID sessionId, ToolCall call) {
        Objects.requireNonNull(sessionId, "Approval requires a session");
        Objects.requireNonNull(call, "Tool call is required");
        return approvalQueue.request(sessionId, call, "Approval required for tool: " + call.name());
    }
}
