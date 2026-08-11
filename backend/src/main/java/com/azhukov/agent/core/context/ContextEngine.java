package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TokenUsage;

import java.util.List;
import java.util.Map;

public interface ContextEngine {

    List<Message> prepareContext(Session session, List<Message> messages);

    /**
     * Preflight check: estimate whether compression should be triggered before the model API call.
     * Returns true if estimated tokens exceed 80% of maxTokens.
     * Default is false (no preflight compression).
     */
    default boolean shouldCompressPreflight(List<Message> messages) {
        return false;
    }

    /**
     * Update tracked token usage from an API response.
     * Called after every LLM call with real token counts extracted from the API response.
     * Replaces the chars/4 estimate with real usage data for accurate budget tracking.
     */
    default void updateFromResponse(TokenUsage usage) {
    }

    /**
     * P2-16: Get the current context engine status for display/logging.
     * Returns a map of status fields (token counts, compression count, usage percentage, etc.).
     * Default returns an empty map.
     */
    default Map<String, Object> getStatus() {
        return Map.of();
    }

    /**
     * P2-16: Update the model and recalculate context length from model metadata.
     * Called when the model override changes for a session.
     * Default is a no-op.
     *
     * @param model the new model name
     */
    default void updateModel(String model) {
    }
}