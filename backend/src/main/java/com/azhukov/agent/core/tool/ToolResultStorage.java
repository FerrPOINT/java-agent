package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.azhukov.agent.core.model.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Feature 8: Tool result persistence — spills large outputs to disk.
 *
 * Mirrors Hermes tools/tool_result_storage.py — maybe_persist_tool_result().
 * When tool output exceeds threshold (default 50KB), writes full output to
 * temp file, replaces in-context with: "[Full output saved to {path}]\n{preview}...\n[truncated]"
 *
 * 3-tier: per-tool cap → per-result persist → per-turn aggregate budget (200KB)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultStorage {

    private final AgentProperties properties;
    private static final int PREVIEW_CHARS = 2000;

    /**
     * Persist oversized tool result to a temp file, return preview + path.
     * If content is small enough, return it unchanged.
     *
     * @param content raw tool result string
     * @param toolName name of the tool
     * @param toolCallId unique ID for this tool call
     * @return original content if small, or persisted-output replacement
     */
    public String maybePersist(String content, String toolName, String toolCallId) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        int threshold = properties.getToolOutput().getPersistThresholdBytes();
        if (threshold <= 0) {
            threshold = 51200; // 50KB default
        }

        if (content.length() <= threshold) {
            return content;
        }

        // Write to temp file
        String fileName = (toolCallId != null ? toolCallId : UUID.randomUUID().toString()) + ".txt";
        Path tempDir;
        Path outputPath;
        try {
            tempDir = Path.of(System.getProperty("java.io.tmpdir"), "java-agent-results");
            Files.createDirectories(tempDir);
            outputPath = tempDir.resolve(fileName);
            Files.writeString(outputPath, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist tool result for {}: {}", toolName, e.getMessage());
            // Fallback: inline truncation
            return generatePreview(content, PREVIEW_CHARS)
                + "\n\n[Truncated: tool response was " + content.length() + " chars. "
                + "Full output could not be saved to disk.]";
        }

        log.info("Persisted large tool result: {} ({} chars -> {})", toolName, content.length(), outputPath);

        String sizeStr = formatSize(content.length());
        String preview = generatePreview(content, PREVIEW_CHARS);
        boolean hasMore = preview.length() < content.length();

        StringBuilder sb = new StringBuilder();
        sb.append("[Full output saved to ").append(outputPath).append("]\n");
        sb.append("Tool result was too large (").append(content.length()).append(" chars, ").append(sizeStr).append(").\n");
        sb.append("Preview (first ").append(preview.length()).append(" chars):\n");
        sb.append(preview);
        if (hasMore) {
            sb.append("\n...\n[truncated]");
        }
        return sb.toString();
    }

    /**
     * Apply persistence to a ToolResult.
     */
    public ToolResult maybePersist(ToolResult result, String toolName, String toolCallId) {
        if (result.success()) {
            String persisted = maybePersist(result.content(), toolName, toolCallId);
            if (persisted == result.content()) {
                return result; // unchanged
            }
            return ToolResult.ok(persisted);
        }
        return result; // Don't persist error results
    }

    /**
     * Enforce per-turn aggregate budget across all tool results.
     * If total exceeds budget, persist the largest non-persisted results first.
     *
     * @param contents list of tool result content strings
     * @param toolCallIds corresponding tool call IDs
     * @return list of possibly-modified content strings
     */
    public java.util.List<String> enforceTurnBudget(java.util.List<String> contents, java.util.List<String> toolCallIds) {
        int budget = properties.getToolOutput().getTurnBudgetBytes();
        if (budget <= 0) budget = 204800;

        int totalSize = contents.stream().mapToInt(String::length).sum();
        if (totalSize <= budget) {
            return contents;
        }

        // Find largest non-persisted results and persist them
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).contains("[Full output saved to")) {
                indices.add(i);
            }
        }
        indices.sort((a, b) -> Integer.compare(contents.get(b).length(), contents.get(a).length()));

        java.util.List<String> result = new java.util.ArrayList<>(contents);
        for (int idx : indices) {
            if (totalSize <= budget) break;
            String original = result.get(idx);
            String persisted = maybePersist(original, "__budget_enforcement__",
                toolCallIds.size() > idx ? toolCallIds.get(idx) : "budget_" + idx);
            if (!persisted.equals(original)) {
                totalSize -= original.length();
                totalSize += persisted.length();
                result.set(idx, persisted);
                log.info("Budget enforcement: persisted tool result {} ({} chars)",
                    toolCallIds.size() > idx ? toolCallIds.get(idx) : idx, original.length());
            }
        }
        return result;
    }

    private String generatePreview(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        String truncated = content.substring(0, maxChars);
        int lastNl = truncated.lastIndexOf('\n');
        if (lastNl > maxChars / 2) {
            truncated = truncated.substring(0, lastNl + 1);
        }
        return truncated;
    }

    private String formatSize(int chars) {
        double kb = chars / 1024.0;
        if (kb >= 1024) {
            return String.format("%.1f MB", kb / 1024);
        }
        return String.format("%.1f KB", kb);
    }
}