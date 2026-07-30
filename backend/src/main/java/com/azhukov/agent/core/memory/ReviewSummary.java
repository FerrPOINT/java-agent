package com.azhukov.agent.core.memory;

import java.util.List;

/**
 * S3: Immutable summary of a background review pass — what actions were taken,
 * whether memory was updated, whether skills were updated.
 * <p>
 * Used to surface a user-facing notification like:
 * "💾 Self-improvement review: Memory updated · Skill 'java-debugging' patched"
 */
public record ReviewSummary(
    boolean memoryUpdated,
    int memoryActions,
    int skillActions,
    List<String> actions,
    String formattedSummary
) {
    /**
     * Build a review summary from a list of action descriptions.
     *
     * @param memoryUpdated whether the review produced memory writes
     * @param actions        the list of action descriptions from tool results
     * @return a ReviewSummary
     */
    public static ReviewSummary of(boolean memoryUpdated, List<String> actions) {
        if (actions == null) {
            actions = List.of();
        }
        int memoryActions = 0;
        int skillActions = 0;
        for (String action : actions) {
            String lower = action.toLowerCase();
            if (lower.startsWith("memory") || lower.startsWith("user profile")) {
                memoryActions++;
            } else if (lower.startsWith("skill") || lower.contains("skill")) {
                skillActions++;
            }
        }
        String formatted = formatSummary(memoryUpdated, actions);
        return new ReviewSummary(memoryUpdated, memoryActions, skillActions, actions, formatted);
    }

    /**
     * Build a user-facing summary string from the action list.
     */
    static String formatSummary(boolean memoryUpdated, List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        // Deduplicate preserving order
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(actions);
        boolean first = true;
        for (String action : seen) {
            if (!first) {
                sb.append(" · ");
            }
            sb.append(action);
            first = false;
        }
        String joined = sb.toString();
        if (memoryUpdated && memoryActionsCount(actions) > 0) {
            return "💾 Self-improvement review: " + joined;
        }
        return "💾 Self-improvement review: " + joined;
    }

    private static int memoryActionsCount(List<String> actions) {
        int count = 0;
        for (String a : actions) {
            if (a.toLowerCase().startsWith("memory") || a.toLowerCase().startsWith("user profile")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if this summary has any actions to surface.
     */
    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    /**
     * An empty summary (no actions taken).
     */
    public static ReviewSummary empty() {
        return new ReviewSummary(false, 0, 0, List.of(), "");
    }
}