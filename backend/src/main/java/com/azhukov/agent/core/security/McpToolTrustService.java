package com.azhukov.agent.core.security;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures operator-side MCP server trust and discovery-time readOnlyHint metadata.
 */
@Component
public class McpToolTrustService {

    public static final String TRUST_FULL = "full";
    public static final String TRUST_UNTRUSTED = "untrusted";

    private volatile Map<ToolName, ToolTrustMetadata> tools = Map.of();

    // A missing server denotes the provider's generated name, not an MCP name.
    private record ToolName(String server, String name) {}

    static String normalizeServerTrust(String value) {
        if (value == null || value.isBlank()) {
            return TRUST_FULL;
        }
        String normalized = value.strip().toLowerCase();
        if (TRUST_FULL.equals(normalized) || TRUST_UNTRUSTED.equals(normalized)) {
            return normalized;
        }
        return TRUST_UNTRUSTED;
    }

    public synchronized void recordServerTools(String serverName, String trust, List<McpSchema.Tool> definitions) {
        if (serverName == null || serverName.isBlank()) return;
        var updated = new HashMap<>(tools);
        updated.values().removeIf(metadata -> serverName.equals(metadata.serverName()));
        for (var definition : definitions) {
            var metadata = ToolTrustMetadata.nativeTool(serverName, trust, definition);
            updated.put(new ToolName(serverName, definition.name()), metadata);
            updated.put(new ToolName(null, McpLifecycleManager.mcpPrefixedToolName(serverName, definition.name())), metadata);
        }
        tools = Map.copyOf(updated);
    }

    public ToolTrustMetadata metadata(String fullToolName) {
        return tools.get(new ToolName(null, fullToolName));
    }

    public ToolTrustMetadata metadata(String serverName, String toolName) {
        return tools.get(new ToolName(serverName, toolName));
    }

    public record ToolTrustMetadata(String serverName, String trust, boolean readOnlyHint, McpSchema.Tool definition) {
        public static ToolTrustMetadata nativeTool(String serverName, String trust, McpSchema.Tool definition) {
            boolean readOnly = definition.annotations() != null
                && Boolean.TRUE.equals(definition.annotations().readOnlyHint());
            return new ToolTrustMetadata(serverName, normalizeServerTrust(trust), readOnly, definition);
        }

        public boolean requiresApproval() {
            return TRUST_UNTRUSTED.equals(trust) && !readOnlyHint;
        }
    }
}
