package com.azhukov.agent.core.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 9: Tool loop guardrails — tracks repeated tool calls and generates
 * warnings when the model appears to be stuck in a loop.
 *
 * Mirrors Hermes agent/tool_guardrails.py — ToolCallGuardrailController.
 * Non-blocking: warnings are appended to tool result, not preventing execution.
 *
 * - After N exact repeats (same tool + same args): soft warning
 * - After M same-tool failures: stronger warning
 */
@Slf4j
@Component
public class ToolLoopGuardrail {

    private final boolean enabled;
    private final int maxExactRepeats;
    private final int maxSameToolFailures;

    // Per-turn tracking — thread-safe for parallel tool execution
    private final Map<String, Integer> exactRepeatCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sameToolFailureCounts = new ConcurrentHashMap<>();

    public ToolLoopGuardrail() {
        this(true, 3, 5);
    }

    public ToolLoopGuardrail(boolean enabled, int maxExactRepeats, int maxSameToolFailures) {
        this.enabled = enabled;
        this.maxExactRepeats = maxExactRepeats;
        this.maxSameToolFailures = maxSameToolFailures;
    }

    /**
     * Reset tracking for a new turn.
     */
    public void resetForTurn() {
        exactRepeatCounts.clear();
        sameToolFailureCounts.clear();
    }

    /**
     * Check before a tool call for repeated patterns.
     *
     * @param toolName the tool name
     * @param arguments the tool arguments JSON string
     * @return warning message if repeated, or null if no warning
     */
    public String beforeCall(String toolName, String arguments) {
        if (!enabled) return null;

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
     * Record a tool call result and check for failure loops.
     *
     * @param toolName the tool name
     * @param arguments the tool arguments JSON string
     * @param failed whether the tool call failed
     * @return warning message if threshold reached, or null
     */
    public String afterCall(String toolName, String arguments, boolean failed) {
        if (!enabled) return null;

        String signature = toolName + ":" + hashArgs(arguments);

        if (failed) {
            int exactCount = exactRepeatCounts.getOrDefault(signature, 0) + 1;
            exactRepeatCounts.put(signature, exactCount);

            int sameCount = sameToolFailureCounts.getOrDefault(toolName, 0) + 1;
            sameToolFailureCounts.put(toolName, sameCount);

            if (sameCount >= maxSameToolFailures) {
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

            if (sameCount >= maxSameToolFailures - 1) {
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
        }
        return null;
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

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxExactRepeats() {
        return maxExactRepeats;
    }

    public int getMaxSameToolFailures() {
        return maxSameToolFailures;
    }
}