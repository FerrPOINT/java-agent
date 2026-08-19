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

    /**
     * Returns the timestamp of the last compression for the given session,
     * or null if no compression has occurred.  Downstream consumers use this
     * to detect compression boundaries without the full session-rotation
     * machinery.
     */
    default java.time.Instant getLastCompressionAt(java.util.UUID sessionId) {
        return null;
    }

    /**
     * Finding 5.2: Count prior user messages for a session directly from the
     * repository, avoiding the expensive full {@link #prepareContext} call.
     * Used by the memory nudge counter to hydrate on session restart.
     *
     * @param sessionId the session UUID
     * @return the number of prior user messages, or 0 if unavailable
     */
    default long countPriorUserMessages(java.util.UUID sessionId) {
        return 0;
    }

    /**
     * Evict all per-session in-memory state for the given session.
     * Called when a session is deleted (see SessionDeletedEvent) so that
     * session-scoped caches do not accumulate forever. Default no-op.
     */
    default void evict(java.util.UUID sessionId) {
    }
}