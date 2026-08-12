package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

/**
 * Shared formatting of {@link ToolResult} for inclusion in the conversation history
 * and for display in SSE events.  Used by both the streaming and sync agentic loops.
 */
@Component
public class ToolResultFormatter {

    /**
     * Format a tool result for inclusion in the message history.
     *
     * <p>On success the raw content is returned; on failure the error is prefixed
     * with {@code "Error: "}.
     *
     * @param result the tool result to format
     * @return formatted string suitable for the model's tool-result message
     */
    public String formatResult(ToolResult result) {
        if (result.success()) {
            return result.content();
        }
        return "Error: " + result.error();
    }
}