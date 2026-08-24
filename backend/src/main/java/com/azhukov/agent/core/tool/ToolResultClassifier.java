package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Classifies a {@link ToolResult} into a broad category for logging and
 * future decision-making.
 *
 * Also provides Hermes-parity helpers (agent/tool_result_classification.py):
 * {@link #toolMayHaveSideEffect(String)} and
 * {@link #fileMutationResultLanded(String, String)} used by replay cleanup
 * and tool guardrails.
 */
@Slf4j
@Component
public class ToolResultClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum ResultType {
        SUCCESS,
        FAILURE,
        PARTIAL,
        SILENT
    }

    // ── Hermes parity (tool_result_classification.py) ──────────────────

    /** Tools whose interrupted/dangling execution is safe to discard. */
    private static final Set<String> NO_EFFECT_TOOL_NAMES = Set.of(
        "read_file", "search_files", "session_search", "skill_view", "skills_list",
        "web_extract", "web_search", "vision_analyze", "browser_snapshot",
        "browser_get_images", "browser_console", "read_terminal"
    );

    /** Tools that mutate files on disk. */
    private static final Set<String> FILE_MUTATING_TOOL_NAMES = Set.of(
        "write_file", "patch"
    );

    /**
     * Return true when a tool may have side effects (external state mutation).
     * Unknown/plugin/MCP tools are effect-capable by default.
     * Hermes parity: {@code tool_may_have_side_effect}.
     */
    public static boolean toolMayHaveSideEffect(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return true; // unknown → conservative
        }
        return !NO_EFFECT_TOOL_NAMES.contains(toolName);
    }

    /**
     * Return true when a file-mutation tool result proves the write landed.
     * Hermes parity: {@code file_mutation_result_landed}.
     */
    public static boolean fileMutationResultLanded(String toolName, String result) {
        if (toolName == null || !FILE_MUTATING_TOOL_NAMES.contains(toolName) || result == null) {
            return false;
        }
        try {
            JsonNode data = MAPPER.readTree(result.strip());
            if (!data.isObject() || data.has("error")) {
                return false;
            }
            if ("write_file".equals(toolName)) {
                return data.has("bytes_written");
            }
            if ("patch".equals(toolName)) {
                return data.has("success") && data.get("success").asBoolean();
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // ── Original classification ─────────────────────────────────────────

    public ResultType classify(ToolResult result) {
        if (!result.success()) {
            return ResultType.FAILURE;
        }
        String content = result.content();
        if (content == null || content.isEmpty() || "***".equals(content)) {
            return ResultType.SILENT;
        }
        String lower = content.toLowerCase();
        if (lower.contains("error") || lower.contains("failed")) {
            return ResultType.PARTIAL;
        }
        return ResultType.SUCCESS;
    }
}
