package com.azhukov.agent.tools.mcp;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@AgentTool(
    name = "mcp_tool",
    description = "Invoke a tool on a connected MCP server.",
    toolset = "core"
)
@Component
@RequiredArgsConstructor
public class McpTool implements ToolHandler {

    private final McpLifecycleManager mcpLifecycleManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        McpArgs args = ToolHandler.parseJson(arguments, McpArgs.class);
        try {
            var result = mcpLifecycleManager.executeTool(args.serverName(), args.toolName(), args.arguments());
            String text = result.content().stream()
                .map(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc ? tc.text() : c.toString())
                .collect(Collectors.joining("\n"));
            return ToolResult.ok(text);
        } catch (Exception e) {
            return ToolResult.fail("MCP tool failed: " + e.getMessage());
        }
    }

    public record McpArgs(
        @ToolParam(description = "MCP server name") @com.fasterxml.jackson.annotation.JsonProperty("server_name") String serverName,
        @ToolParam(description = "tool name") @com.fasterxml.jackson.annotation.JsonProperty("tool_name") String toolName,
        @ToolParam(description = "tool arguments JSON") @com.fasterxml.jackson.annotation.JsonProperty("arguments") String arguments
    ) {}
}
