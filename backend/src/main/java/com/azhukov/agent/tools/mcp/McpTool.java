package com.azhukov.agent.tools.mcp;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.client.mcp.McpOAuthManager;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@AgentTool(
    name = "mcp_tool",
    description = "Invoke a tool on a connected MCP server.",
    toolset = "core"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class McpTool implements ToolHandler {

    private final McpLifecycleManager mcpLifecycleManager;
    private final McpOAuthManager mcpOAuthManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        McpArgs args = ToolHandler.parseJson(arguments, McpArgs.class);
        // A null serverName would surface as a raw ConcurrentHashMap NPE
        // ("Cannot invoke Object.hashCode()...") — give the model usage guidance instead.
        if (args.serverName() == null || args.serverName().isBlank()) {
            return ToolResult.fail("mcp_tool requires 'server_name' (and 'tool_name'). "
                + "No MCP servers are connected; see /mcp/servers for the configured list.");
        }
        mcpOAuthManager.getToken(args.serverName())
            .ifPresent(token -> log.debug("Using OAuth token for MCP server {}", args.serverName()));
        try {
            var result = mcpLifecycleManager.executeTool(args.serverName(), args.toolName(), args.arguments());
            String text = result.content().stream()
                .map(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc ? tc.text() : c.toString())
                .collect(Collectors.joining("\n"));
            // Hermes parity (mcp_tool.py _strip_reserved_meta_keys, MoonshotAI/kimi-code#2600):
            // surface tool-result _meta to the model, but drop protocol-reserved keys —
            // a prefix is reserved when a "modelcontextprotocol" or "mcp" label is followed
            // by at least one more label (tools.mcp.com/...). A trailing reserved word
            // (com.example.mcp/...) is a legitimate vendor namespace and passes through.
            String metaSection = formatMeta(result.meta());
            return ToolResult.ok(metaSection.isEmpty() ? text : text + "\n" + metaSection);
        } catch (Exception e) {
            return ToolResult.fail("MCP tool failed: " + e.getMessage());
        }
    }

    /** Format non-reserved _meta entries as a JSON-ish block; empty when nothing model-facing. */
    static String formatMeta(java.util.Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var e : meta.entrySet()) {
            if (e.getKey() == null || isReservedMetaKey(e.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": ").append(String.valueOf(e.getValue()));
        }
        return sb.isEmpty() ? "" : "[_meta: {" + sb + "}]";
    }

    /** Hermes _is_reserved_mcp_meta_key: reserved prefix = mcp/modelcontextprotocol label + ≥1 more label. */
    static boolean isReservedMetaKey(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String[] labels = key.substring(0, slash).split("\\.");
        for (int i = 0; i < labels.length; i++) {
            if (("modelcontextprotocol".equals(labels[i]) || "mcp".equals(labels[i]))
                && i < labels.length - 1) {
                return true;
            }
        }
        return false;
    }

    public record McpArgs(
        @ToolParam(description = "MCP server name") @com.fasterxml.jackson.annotation.JsonProperty("server_name") String serverName,
        @ToolParam(description = "tool name") @com.fasterxml.jackson.annotation.JsonProperty("tool_name") String toolName,
        @ToolParam(description = "tool arguments JSON") @com.fasterxml.jackson.annotation.JsonProperty("arguments") String arguments
    ) {}
}
