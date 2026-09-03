package com.azhukov.agent.tools.mcp;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.client.mcp.McpOAuthManager;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOL_ERROR_CHARS = 2048;

    private final McpLifecycleManager mcpLifecycleManager;
    private final McpOAuthManager mcpOAuthManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        McpArgs args;
        try {
            args = ToolHandler.parseJson(arguments, McpArgs.class);
        } catch (Exception e) {
            return jsonError("Invalid tool arguments: " + e.getMessage());
        }
        // A null serverName would surface as a raw ConcurrentHashMap NPE
        // ("Cannot invoke Object.hashCode()...") — give the model usage guidance instead.
        if (args.serverName() == null || args.serverName().isBlank()) {
            return jsonError("mcp_tool requires 'server_name' (and 'tool_name'). "
                + "No MCP servers are connected; see /mcp/servers for the configured list.");
        }
        if (args.toolName() == null || args.toolName().isBlank()) {
            return jsonError("mcp_tool requires 'tool_name'.");
        }
        mcpOAuthManager.getToken(args.serverName())
            .ifPresent(token -> log.debug("Using OAuth token for MCP server {}", args.serverName()));
        try {
            String argumentsJson = args.arguments() == null || args.arguments().isBlank() ? "{}" : args.arguments();
            var result = mcpLifecycleManager.executeTool(args.serverName(), args.toolName(), argumentsJson);
            String text = result.content().stream()
                .map(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc ? tc.text() : c.toString())
                .collect(Collectors.joining("\n"));
            // Hermes parity (mcp_tool.py _strip_reserved_meta_keys, MoonshotAI/kimi-code#2600):
            // surface tool-result _meta to the model, but drop protocol-reserved keys —
            // a prefix is reserved when a "modelcontextprotocol" or "mcp" label is followed
            // by at least one more label (tools.mcp.com/...). A trailing reserved word
            // (com.example.mcp/...) is a legitimate vendor namespace and passes through.
            String metaSection = formatMeta(result.meta());
            String output = metaSection.isEmpty() ? text : text + "\n" + metaSection;
            if (Boolean.TRUE.equals(result.isError())) {
                return jsonError(output);
            }
            return ToolResult.ok(output);
        } catch (Exception e) {
            return jsonError("MCP tool failed: " + e.getMessage());
        }
    }

    private static ToolResult jsonError(String message) {
        String error = boundToolError(message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", error);
        try {
            return new ToolResult(false, JSON.writeValueAsString(response), error);
        } catch (Exception e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"MCP tool failed\"}", error);
        }
    }

    private static String boundToolError(String message) {
        String error = message == null || message.isBlank() ? "MCP tool failed" : message;
        return error.length() <= MAX_TOOL_ERROR_CHARS
            ? error
            : error.substring(0, MAX_TOOL_ERROR_CHARS) + "... [truncated]";
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
        @ToolParam(description = "MCP server name") @JsonProperty("server_name") @JsonAlias("serverName") String serverName,
        @ToolParam(description = "tool name") @JsonProperty("tool_name") @JsonAlias("toolName") String toolName,
        @ToolParam(description = "tool arguments JSON") @com.fasterxml.jackson.annotation.JsonProperty("arguments") String arguments
    ) {}
}
