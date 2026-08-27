package com.azhukov.agent.core.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool loop guardrails — tracks repeated tool calls and generates
 * warnings when the model appears to be stuck in a loop.
 *
 * <p>Mirrors Hermes {@code agent/tool_guardrails.py} —
 * {@code ToolCallGuardrailController}. Non-blocking by default: warnings
 * are appended to tool result, not preventing execution.
 *
 * <p>Three detection modes (Hermes parity):
 * <ol>
 *   <li><b>Exact failure repeat</b> — same tool + same args failed N times
 *       → warn after 2, block after 5</li>
 *   <li><b>Same-tool failure</b> — same tool failed M times (any args)
 *       → warn after 3, halt after 8</li>
 *   <li><b>Idempotent no-progress</b> — read-only tool returned the same
 *       result N times → warn after 2, block after 5</li>
 * </ol>
 *
 * <p>Per-turn runaway caps (Hermes parity: {@code LoopCapConfig}):
 * web_search (50/turn), delegate_task (50/turn) — hard ceiling regardless
 * of hard_stop_enabled. Counters reset each turn.
 */
@Slf4j
@Component
public class ToolLoopGuardrail {

    // ── Hermes parity: IDEMPOTENT_TOOL_NAMES (tool_guardrails.py) ──────
    private static final Set<String> IDEMPOTENT_TOOLS = Set.of(
        "read_file", "search_files", "web_search", "web_extract",
        "session_search", "browser_snapshot", "browser_console",
        "browser_get_images", "skill_view", "skills_list",
        "vision_analyze"
    );

    // ── Hermes parity: LoopCapConfig defaults ──────────────────────────
    private static final int DEFAULT_MAX_WEB_SEARCHES = 50;
    private static final int DEFAULT_MAX_SUBAGENTS = 50;

    private final boolean enabled;
    private final int maxExactRepeats;       // warn after
    private final int maxSameToolFailures;   // warn after
    private final int maxIdempotentNoProgress; // warn after
    private final int maxWebSearches;
    private final int maxSubagents;

    // Per-turn tracking — thread-safe for parallel tool execution
    private final Map<String, Integer> exactRepeatCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sameToolFailureCounts = new ConcurrentHashMap<>();
    private final Map<String, String> idempotentResultHashes = new ConcurrentHashMap<>();
    private final Map<String, Integer> idempotentRepeatCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> turnToolCounts = new ConcurrentHashMap<>();

    // ── P-07 (Hermes 761990b780): identical-result reference stubbing ──
    /** Results below this size are never stubbed (stub overhead > savings). */
    static final int IDENTICAL_RESULT_STUB_MIN_CHARS = 512;
    /** How much of the canonical args the stub carries (dangling-ref mitigation). */
    private static final int RESULT_STUB_ARGS_PREVIEW_CHARS = 120;
    // signature -> last successful result hash (stub streak tracking)
    private final Map<String, String> stubStreakResultHashes = new ConcurrentHashMap<>();
    // signature -> streak length of byte-identical consecutive results
    private final Map<String, Integer> stubStreakCounts = new ConcurrentHashMap<>();

    public ToolLoopGuardrail() {
        this(true, 2, 3, 2, DEFAULT_MAX_WEB_SEARCHES, DEFAULT_MAX_SUBAGENTS);
    }

    public ToolLoopGuardrail(boolean enabled, int maxExactRepeats, int maxSameToolFailures) {
        this(enabled, maxExactRepeats, maxSameToolFailures, 2,
             DEFAULT_MAX_WEB_SEARCHES, DEFAULT_MAX_SUBAGENTS);
    }

    public ToolLoopGuardrail(boolean enabled, int maxExactRepeats, int maxSameToolFailures,
                             int maxIdempotentNoProgress, int maxWebSearches, int maxSubagents) {
        this.enabled = enabled;
        this.maxExactRepeats = maxExactRepeats;
        this.maxSameToolFailures = maxSameToolFailures;
        this.maxIdempotentNoProgress = maxIdempotentNoProgress;
        this.maxWebSearches = maxWebSearches;
        this.maxSubagents = maxSubagents;
    }

    /**
     * Reset tracking for a new turn.
     */
    public void resetForTurn() {
        exactRepeatCounts.clear();
        sameToolFailureCounts.clear();
        idempotentResultHashes.clear();
        idempotentRepeatCounts.clear();
        turnToolCounts.clear();
        stubStreakResultHashes.clear();
        stubStreakCounts.clear();
    }

    /**
     * Check before a tool call for repeated patterns and runaway caps.
     *
     * @return warning/block message, or null if no issue
     */
    public String beforeCall(String toolName, String arguments) {
        if (!enabled) return null;

        // ── Runaway caps (hard ceiling, always enforced) ───────────────
        String capWarning = checkRunawayCap(toolName);
        if (capWarning != null) return capWarning;

        // ── Exact repeat check ─────────────────────────────────────────
        String signature = toolName + ":" + hashArgs(arguments);
        int count = exactRepeatCounts.getOrDefault(signature, 0);
        if (count >= maxExactRepeats) {
            return String.format(
                "Tool loop warning: %s has been called %d times with identical arguments. " +
                "This looks like a loop; inspect the error and change strategy " +
                "instead of retrying it unchanged.",
                toolName, count
            );
        }
        return null;
    }

    /**
     * Compatibility overload for existing callers that do not retain tool
     * content. Without a result, no-progress tracking intentionally skips.
     */
    public String afterCall(String toolName, String arguments, boolean failed) {
        return afterCall(toolName, arguments, null, failed);
    }

    /**
     * Record a tool call result and check for failure loops + no-progress.
     *
     * @param toolName the tool name
     * @param arguments the tool arguments JSON string
     * @param result the tool result content (for idempotent hash)
     * @param failed whether the tool call failed
     * @return warning message if threshold reached, or null
     */
    public String afterCall(String toolName, String arguments, String result, boolean failed) {
        if (!enabled) return null;

        String signature = toolName + ":" + hashArgs(arguments);

        if (failed) {
            int exactCount = exactRepeatCounts.getOrDefault(signature, 0) + 1;
            exactRepeatCounts.put(signature, exactCount);

            int sameCount = sameToolFailureCounts.getOrDefault(toolName, 0) + 1;
            sameToolFailureCounts.put(toolName, sameCount);

            if (sameCount >= maxSameToolFailures + 5) {
                return String.format(
                    "Tool loop hard warning: %s has failed %d times this turn. " +
                    "Stop retrying the same failing tool path and choose a different approach. " +
                    "Try different arguments, a narrower query/path, or a different tool.",
                    toolName, sameCount
                );
            }

            if (exactCount >= maxExactRepeats) {
                return String.format(
                    "Tool loop warning: %s has failed %d times with identical arguments. " +
                    "This looks like a loop; change strategy instead of retrying unchanged.",
                    toolName, exactCount
                );
            }

            if (sameCount >= maxSameToolFailures) {
                return String.format(
                    "Tool loop warning: %s has failed %d times this turn. " +
                    "Diagnose the error before retrying.",
                    toolName, sameCount
                );
            }
        } else {
            // Success — reset failure counts for this signature
            exactRepeatCounts.remove(signature);
            sameToolFailureCounts.remove(toolName);

            // ── Idempotent no-progress detection ───────────────────────
            if (isIdempotent(toolName) && result != null) {
                String resultHash = hashResult(result);
                String prevHash = idempotentResultHashes.get(signature);
                int repeatCount = idempotentRepeatCounts.getOrDefault(signature, 0);

                if (prevHash != null && prevHash.equals(resultHash)) {
                    repeatCount++;
                } else {
                    repeatCount = 1;
                }
                idempotentResultHashes.put(signature, resultHash);
                idempotentRepeatCounts.put(signature, repeatCount);

                if (repeatCount >= maxIdempotentNoProgress) {
                    return String.format(
                        "Tool loop warning: %s returned the same result %d times. " +
                        "Use the result already provided or change the query instead of " +
                        "repeating it unchanged.",
                        toolName, repeatCount
                    );
                }
            }
        }
        return null;
    }

    /**
     * P-07 (Hermes 761990b780 identical-result stubbing): from the 2nd
     * consecutive SUCCESSFUL call whose fresh result is byte-identical to the
     * previous one and at least {@value #IDENTICAL_RESULT_STUB_MIN_CHARS}
     * chars, the duplicate payload is replaced in context by a short
     * reference stub. The tool still executed — only the context
     * representation is deduplicated. Failed results are never stubbed, and a
     * changed result resets the streak.
     *
     * @return replacement stub content, or null to keep the original result
     */
    public String resultReferenceStub(String toolName, String arguments, String result, boolean failed) {
        if (!enabled || failed || result == null || result.length() < IDENTICAL_RESULT_STUB_MIN_CHARS) {
            // Reset streak tracking on failure/short result for this signature
            if (result != null || failed) {
                String sig = toolName + ":" + hashArgs(arguments);
                stubStreakResultHashes.remove(sig);
                stubStreakCounts.remove(sig);
            }
            return null;
        }
        String signature = toolName + ":" + hashArgs(arguments);
        String resultHash = hashResult(result);
        String prevHash = stubStreakResultHashes.put(signature, resultHash);
        if (prevHash == null || !prevHash.equals(resultHash)) {
            stubStreakCounts.put(signature, 1);
            return null; // first occurrence (or changed) — keep full payload
        }
        int streak = stubStreakCounts.merge(signature, 1, Integer::sum);
        if (streak < 2) {
            return null;
        }
        String argsPreview = arguments != null && arguments.length() > RESULT_STUB_ARGS_PREVIEW_CHARS
            ? arguments.substring(0, RESULT_STUB_ARGS_PREVIEW_CHARS) + "…"
            : (arguments == null ? "" : arguments);
        return "[Duplicate result — identical to the previous " + toolName
            + " result in this turn (call " + streak + " of the streak). "
            + "Refer to the full payload above instead of repeating the call. "
            + "Args: " + argsPreview + "]";
    }

    /**
     * Check per-turn runaway caps for web_search and delegate_task.
     * Hermes parity: LoopCapConfig — hard ceiling regardless of hard_stop.
     */
    private String checkRunawayCap(String toolName) {
        if ("web_search".equals(toolName)) {
            int count = turnToolCounts.getOrDefault("web_search", 0);
            if (maxWebSearches > 0 && count >= maxWebSearches) {
                return String.format(
                    "Blocked web_search: this turn has already made %d " +
                    "web searches, the per-turn limit. Work with the results " +
                    "you already have and give the user your answer.",
                    count
                );
            }
            turnToolCounts.merge("web_search", 1, Integer::sum);
        }

        if ("delegate_task".equals(toolName)) {
            int count = turnToolCounts.getOrDefault("delegate_task", 0);
            if (maxSubagents > 0 && count >= maxSubagents) {
                return String.format(
                    "Blocked delegate_task: this turn has already spawned " +
                    "%d subagents (limit %d). Finish the work with the results " +
                    "you have and answer the user.",
                    count, maxSubagents
                );
            }
            turnToolCounts.merge("delegate_task", 1, Integer::sum);
        }

        return null;
    }

    private boolean isIdempotent(String toolName) {
        return IDEMPOTENT_TOOLS.contains(toolName);
    }

    /**
     * Append a warning to a tool result string.
     */
    public static String appendWarning(String result, String warning) {
        if (warning == null || warning.isEmpty()) {
            return result;
        }
        return (result != null ? result : "") + "\n\n[Tool loop guardrail: " + warning + "]";
    }

    private String hashArgs(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(arguments.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toString(arguments.hashCode());
        }
    }

    private String hashResult(String result) {
        if (result == null || result.isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(result.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toString(result.hashCode());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}