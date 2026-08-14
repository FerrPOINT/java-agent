package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;

import java.util.List;

public interface ContextCompressor {

    List<Message> compress(List<Message> messages, int targetChars);

    boolean isLocked(String sessionId, int generation);

    /**
     * P2-51: Recalculate the compression threshold when the model switches,
     * because different models have different context window sizes.
     * <p>
     * The threshold represents the character count at which compression should
     * be triggered, derived as a fraction (default 0.75) of the new model's
     * context window (in tokens), converted to chars.
     *
     * @param newContextWindowSize the new model's context window size in tokens
     */
    default void recalculateThreshold(int newContextWindowSize) {
    }
}
