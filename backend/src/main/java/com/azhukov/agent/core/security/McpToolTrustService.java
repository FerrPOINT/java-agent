package com.azhukov.agent.core.security;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures operator-side MCP server trust and discovery-time readOnlyHint metadata.
 */
@Component
public class McpToolTrustService {

    public static final String TRUST_FULL = "full";
    public static final String TRUST_UNTRUSTED = "untrusted";

    private final Map<String, String> serverTrust = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> serverToolNames = new ConcurrentHashMap<>();
    private final Map<String, ToolTrustMetadata> toolMetadata = new ConcurrentHashMap<>();

    public static String normalizeServerTrust(String value) {
        if (value == null || value.isBlank()) {
            return TRUST_FULL;
        }
        String normalized = value.strip().toLowerCase();
        if (TRUST_FULL.equals(normalized) || TRUST_UNTRUSTED.equals(normalized)) {
            return normalized;
        }
        return TRUST_UNTRUSTED;
    }

    public void recordServerTools(String serverName, String trust, Map<String, McpSchema.Tool> definitions) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        String normalizedTrust = normalizeServerTrust(trust);
        serverTrust.put(serverName, normalizedTrust);

        Set<String> newToolNames = Set.copyOf(definitions.keySet());
        Set<String> previousToolNames = serverToolNames.put(serverName, newToolNames);
        if (previousToolNames != null) {
            for (String oldToolName : previousToolNames) {
                if (!newToolNames.contains(oldToolName)) {
                    toolMetadata.remove(oldToolName);
                }
            }
        }
        definitions.forEach((name, definition) ->
            toolMetadata.put(name, ToolTrustMetadata.nativeTool(serverName, normalizedTrust, definition)));
    }

    public void recordTool(String serverName, String trust, String fullToolName, boolean readOnlyHint) {
        if (serverName == null || serverName.isBlank() || fullToolName == null || fullToolName.isBlank()) {
            return;
        }
        String normalizedTrust = normalizeServerTrust(trust);
        serverTrust.put(serverName, normalizedTrust);
        serverToolNames.merge(serverName, Set.of(fullToolName), (oldSet, newSet) -> {
            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(oldSet);
            merged.addAll(newSet);
            return Set.copyOf(merged);
        });
        toolMetadata.put(fullToolName, new ToolTrustMetadata(serverName, normalizedTrust, readOnlyHint, null));
    }

    public boolean requiresApproval(String fullToolName) {
        ToolTrustMetadata metadata = toolMetadata.get(fullToolName);
        return metadata != null && metadata.requiresApproval();
    }

    public ToolTrustMetadata metadata(String fullToolName) {
        return toolMetadata.get(fullToolName);
    }

    public String serverTrust(String serverName) {
        return serverTrust.getOrDefault(serverName, TRUST_FULL);
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
