package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared token estimation logic used by both the streaming and sync agentic loops.
 * Estimates tokens as {@code chars / 4 + 1}, counting message content and tool-call
 * arguments/names.
 */
@Component
public class TokenEstimator {

    /**
     * Estimate the number of tokens in a list of messages.
     *
     * @param messages the messages to estimate (may be empty)
     * @return estimated token count ({@code chars / 4 + 1})
     */
    public int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message m : messages) {
            chars += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    chars += tc.arguments() != null ? tc.arguments().length() : 0;
                    chars += tc.name() != null ? tc.name().length() : 0;
                }
            }
        }
        return chars / 4 + 1;
    }
}