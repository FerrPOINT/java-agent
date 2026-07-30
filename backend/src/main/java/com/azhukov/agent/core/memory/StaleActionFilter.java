package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S7: Filters out stale tool results from prior conversation that may re-surface
 * as new actions during background review.
 * <p>
 * Ported from Hermes' {@code summarize_background_review_actions} — the prior-snapshot
 * filtering logic that skips tool messages already present in the conversation history
 * inherited by the review agent (issue #14944).
 * <p>
 * Matching is by {@code toolCallId} when available, with a content-equality fallback
 * for tool messages that lack one.
 */
public final class StaleActionFilter {

    private StaleActionFilter() {}

    /**
     * Collect tool-call IDs and tool-result contents from the prior conversation snapshot.
     *
     * @param priorMessages the conversation messages the review agent inherits
     * @return a set of tool-call IDs and tool-result contents to skip
     */
    public static PriorToolResults collectPriorToolResults(List<Message> priorMessages) {
        Set<String> toolCallIds = new HashSet<>();
        Set<String> toolContents = new HashSet<>();
        if (priorMessages == null) {
            return new PriorToolResults(toolCallIds, toolContents);
        }
        for (Message msg : priorMessages) {
            if (msg.role() != Role.TOOL) continue;
            if (msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
                toolCallIds.add(msg.toolCallId());
            } else if (msg.content() != null) {
                toolContents.add(msg.content());
            }
        }
        return new PriorToolResults(toolCallIds, toolContents);
    }

    /**
     * Check if a tool result message is stale (already in the prior conversation).
     *
     * @param msg         the message to check
     * @param priorResults the prior tool results to compare against
     * @return true if the message is a stale duplicate from prior conversation
     */
    public static boolean isStale(Message msg, PriorToolResults priorResults) {
        if (msg == null || msg.role() != Role.TOOL) {
            return false;
        }
        if (msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
            return priorResults.toolCallIds().contains(msg.toolCallId());
        }
        if (msg.content() != null) {
            return priorResults.toolContents().contains(msg.content());
        }
        return false;
    }

    /**
     * Immutable container for prior tool results.
     */
    public record PriorToolResults(
        Set<String> toolCallIds,
        Set<String> toolContents
    ) {}
}