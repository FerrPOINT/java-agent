package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hook invoked at the end of every turn (success or failure) to perform cleanup
 * operations: evict prompt cache on failure, update session timestamp, log turn metrics.
 * <p>
 * Additionally, this finalizer provides two user-visible enhancements:
 * <ol>
 *   <li><b>File-mutation verifier</b> — scans the turn's tool calls for failed
 *       {@code write_file}/{@code patch} calls that were never superseded by a
 *       successful write to the same path. If found, appends an advisory footer
 *       to the assistant response.</li>
 *   <li><b>Turn-completion explainer</b> — when a turn ends abnormally (empty
 *       content, partial fragment, pending tool result, budget limit, etc.),
 *       adds a user-visible explanation derived from the turn exit reason.</li>
 * </ol>
 * <p>
 * Message persistence is handled by {@code AgentRuntimeService} — the finalizer
 * does NOT duplicate that work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TurnFinalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MUTATION_TOOLS = Set.of("write_file", "patch");

    /** Tools whose result content starts with this prefix are considered failed. */
    private static final String ERROR_PREFIX = "Error:";

    private final PromptCacheTracker promptCacheTracker;

    /**
     * Finalize a turn with a specific exit reason.
     *
     * @param sessionId  the session UUID
     * @param messages   all messages produced during the turn (system + user + assistant + tool results)
     * @param success    whether the turn completed successfully
     * @param exitReason the reason the turn ended
     * @return a result object containing the advisory footer and/or completion explanation, or null if nothing to add
     */
    public FinalizationResult finalize(UUID sessionId, List<Message> messages, boolean success, TurnExitReason exitReason) {
        int msgCount = messages != null ? messages.size() : 0;

        if (!success) {
            promptCacheTracker.invalidate(sessionId.toString());
            log.debug("Turn FAILED for session {}: {} messages, prompt cache evicted", sessionId, msgCount);
        } else {
            log.debug("Turn completed for session {}: {} messages, prompt cache preserved", sessionId, msgCount);
        }

        if (messages == null || messages.isEmpty()) {
            // Even with no messages, if there's an abnormal exit reason, return explanation
            if (exitReason != null && exitReason.isAbnormal()) {
                String explanation = exitReason.explanation();
                if (explanation != null) {
                    return new FinalizationResult(null, explanation);
                }
            }
            return null;
        }

        String footer = buildFileMutationFooter(messages);
        String explanation = buildCompletionExplanation(messages, exitReason);

        if (footer != null || explanation != null) {
            return new FinalizationResult(footer, explanation);
        }
        return null;
    }

    /**
     * Finalize a turn. Backwards-compatible overload that infers the exit reason
     * from the success flag and message content.
     *
     * @param sessionId  the session UUID
     * @param messages   all messages produced during the turn
     * @param success    whether the turn completed successfully
     * @return a result object containing the advisory footer and/or completion explanation, or null if nothing to add
     */
    public FinalizationResult finalize(UUID sessionId, List<Message> messages, boolean success) {
        TurnExitReason exitReason = inferExitReason(messages, success);
        return finalize(sessionId, messages, success, exitReason);
    }

    /**
     * Infer the most likely exit reason from the messages and success flag.
     * Used by the backwards-compatible {@link #finalize(UUID, List, boolean)} overload.
     */
    static TurnExitReason inferExitReason(List<Message> messages, boolean success) {
        if (!success) {
            if (messages == null || messages.isEmpty()) {
                return TurnExitReason.UNKNOWN;
            }
            // Check last message for specific patterns
            Message last = messages.get(messages.size() - 1);
            if (last.content() != null) {
                if (last.content().contains("budget exhausted") || last.content().contains("Iteration budget")) {
                    return TurnExitReason.BUDGET_EXHAUSTED;
                }
                if (last.content().contains("halted by guardrails") || last.content().contains("guardrail")) {
                    return TurnExitReason.GUARDRAIL_HALTED;
                }
                if (last.content().contains("cancelled by user") || last.content().contains("cancelled")) {
                    return TurnExitReason.INTERRUPTED;
                }
                if (last.content().contains("Model call failed")) {
                    return TurnExitReason.MODEL_CALL_FAILED;
                }
                if (last.content().contains("max turns") || last.content().contains("Reached max turns")) {
                    return TurnExitReason.MAX_TURNS_REACHED;
                }
            }
            // If last message is a tool result, it's a pending tool result scenario
            if (last.role() == Role.TOOL) {
                return TurnExitReason.PENDING_TOOL_RESULT;
            }
            return TurnExitReason.UNKNOWN;
        }
        // Success path — check for empty content
        if (messages != null && !messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last.role() == Role.ASSISTANT) {
                if (last.content() == null || last.content().isBlank()) {
                    return TurnExitReason.EMPTY_RESPONSE;
                }
            }
            if (last.role() == Role.TOOL) {
                return TurnExitReason.PENDING_TOOL_RESULT;
            }
        }
        return TurnExitReason.COMPLETED;
    }

    /**
     * Build the turn-completion explanation for abnormal exits.
     * <p>
     * Mirrors the Hermes turn_finalizer logic:
     * <ul>
     *   <li>Empty/blank final assistant content → replace with explanation</li>
     *   <li>Short partial fragment (≤24 chars, no terminal punctuation) → append explanation</li>
     *   <li>Normal text responses are left alone</li>
     * </ul>
     */
    private String buildCompletionExplanation(List<Message> messages, TurnExitReason exitReason) {
        if (exitReason == null || !exitReason.isAbnormal()) {
            return null;
        }

        // Find the last assistant message
        Message lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Role.ASSISTANT) {
                lastAssistant = m;
                break;
            }
        }

        String explanation = exitReason.explanation();
        if (explanation == null) {
            return null;
        }

        String content = lastAssistant != null ? lastAssistant.content() : null;
        String stripped = content != null ? content.strip() : "";

        boolean isEmpty = stripped.isEmpty() || stripped.equals("(empty)");
        boolean isPartialFragment = !isEmpty
                && len(stripped) <= 24
                && !hasTerminalPunctuation(stripped);

        if (isEmpty || isPartialFragment) {
            return explanation;
        }
        return null;
    }

    private static int len(String s) {
        return s != null ? s.length() : 0;
    }

    private static boolean hasTerminalPunctuation(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '`' || c == ')';
    }

    /**
     * Scan the turn's messages for failed write_file/patch calls that were never
     * superseded by a successful write to the same path.
     * <p>
     * Returns an advisory footer string, or null if no unsuperseded failures found.
     *
     * @param messages all messages from the turn
     * @return advisory footer, or null
     */
    private String buildFileMutationFooter(List<Message> messages) {
        // Collect all (toolCallId → ToolCall) from assistant messages, in order
        // Collect all tool results keyed by toolCallId
        Map<String, ToolCall> toolCallById = new LinkedHashMap<>();
        Map<String, Boolean> resultSuccessByCallId = new LinkedHashMap<>();

        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    if (MUTATION_TOOLS.contains(tc.name())) {
                        toolCallById.put(tc.id(), tc);
                    }
                }
            }
            if (msg.role() == Role.TOOL && msg.toolCallId() != null) {
                boolean isSuccess = msg.content() != null && !msg.content().startsWith(ERROR_PREFIX);
                resultSuccessByCallId.put(msg.toolCallId(), isSuccess);
            }
        }

        if (toolCallById.isEmpty()) {
            return null;
        }

        // Build ordered list of (path, success) for each mutation tool call
        // Track which paths had at least one successful write AFTER any failure
        // We need to check ordering: a failed write to path X that is later
        // followed by a successful write to path X is considered superseded.

        // First, collect all mutation calls in order with their paths and success status
        List<MutationRecord> mutations = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    if (MUTATION_TOOLS.contains(tc.name())) {
                        String path = extractPath(tc);
                        if (path != null) {
                            Boolean success = resultSuccessByCallId.get(tc.id());
                            mutations.add(new MutationRecord(path, success != null && success, tc.id()));
                        }
                    }
                }
            }
            // Also check tool results that might correspond to earlier tool calls
            // We process in message order, so we track successes as we go
        }

        if (mutations.isEmpty()) {
            return null;
        }

        // Find failed mutations that were NOT followed by a successful write to the same path
        Set<String> unsupersededFailedPaths = new LinkedHashSet<>();
        for (int i = 0; i < mutations.size(); i++) {
            MutationRecord m = mutations.get(i);
            if (!m.success) {
                // Check if any subsequent mutation to the same path succeeded
                boolean superseded = false;
                for (int j = i + 1; j < mutations.size(); j++) {
                    if (mutations.get(j).path.equals(m.path) && mutations.get(j).success) {
                        superseded = true;
                        break;
                    }
                }
                if (!superseded) {
                    unsupersededFailedPaths.add(m.path);
                }
            }
        }

        if (unsupersededFailedPaths.isEmpty()) {
            return null;
        }

        // Build footer
        StringBuilder sb = new StringBuilder();
        for (String path : unsupersededFailedPaths) {
            sb.append("\n⚠ Note: A file write to ").append(path).append(" failed earlier in this turn and was not retried.");
        }
        return sb.toString();
    }

    /**
     * Extract the file path from a tool call's arguments JSON.
     * Handles write_file (path field) and patch (path field in replace mode, or
     * extracts paths from V4A patch content in patch mode).
     */
    private String extractPath(ToolCall tc) {
        try {
            JsonNode root = JSON.readTree(tc.arguments());
            if (root.has("path") && !root.get("path").isNull()) {
                return root.get("path").asText();
            }
            // For V4A patch mode, extract the first file path from the patch content
            if ("patch".equals(tc.name()) && root.has("patch") && !root.get("patch").isNull()) {
                String patchContent = root.get("patch").asText();
                // V4A format: *** Update File: path/to/file or *** Add File: path/to/file
                java.util.regex.Pattern headerPattern = java.util.regex.Pattern.compile(
                    "\\*\\*\\*\\s*(?:Update|Add|Delete|Move)\\s+File:\\s*(.+?)\\s*$",
                    java.util.regex.Pattern.MULTILINE);
                java.util.regex.Matcher m = headerPattern.matcher(patchContent);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract path from tool call {}: {}", tc.name(), e.getMessage());
        }
        return null;
    }

    /**
     * Result of finalization: optional advisory footer and/or completion explanation
     * to append to the assistant response.
     */
    public record FinalizationResult(String fileMutationFooter, String completionExplanation) {}

    private record MutationRecord(String path, boolean success, String toolCallId) {}
}