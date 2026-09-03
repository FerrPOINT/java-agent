package com.azhukov.agent.core.security;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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

    public String normalizeServerTrust(String value) {
        if (value == null || value.isBlank()) {
            return TRUST_FULL;
        }
        String normalized = value.strip().toLowerCase();
        if (TRUST_FULL.equals(normalized) || TRUST_UNTRUSTED.equals(normalized)) {
            return normalized;
        }
        return TRUST_UNTRUSTED;
    }

    public void recordServerTools(String serverName, String trust, Map<String, Boolean> readOnlyByFullToolName) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        String normalizedTrust = normalizeServerTrust(trust);
        serverTrust.put(serverName, normalizedTrust);

        Map<String, Boolean> tools = readOnlyByFullToolName == null
            ? Map.of()
            : new LinkedHashMap<>(readOnlyByFullToolName);
        Set<String> newToolNames = Set.copyOf(tools.keySet());
        Set<String> previousToolNames = serverToolNames.put(serverName, newToolNames);
        if (previousToolNames != null) {
            for (String oldToolName : previousToolNames) {
                if (!newToolNames.contains(oldToolName)) {
                    toolMetadata.remove(oldToolName);
                }
            }
        }
        for (Map.Entry<String, Boolean> entry : tools.entrySet()) {
            toolMetadata.put(entry.getKey(), new ToolTrustMetadata(
                serverName,
                normalizedTrust,
                Boolean.TRUE.equals(entry.getValue())));
        }
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
        toolMetadata.put(fullToolName, new ToolTrustMetadata(serverName, normalizedTrust, readOnlyHint));
    }

    public boolean requiresApproval(String fullToolName) {
        ToolTrustMetadata metadata = toolMetadata.get(fullToolName);
        return metadata != null
            && TRUST_UNTRUSTED.equals(metadata.trust())
            && !metadata.readOnlyHint();
    }

    public ToolTrustMetadata metadata(String fullToolName) {
        return toolMetadata.get(fullToolName);
    }

    public String serverTrust(String serverName) {
        return serverTrust.getOrDefault(serverName, TRUST_FULL);
    }

    public record ToolTrustMetadata(String serverName, String trust, boolean readOnlyHint) {}
}
