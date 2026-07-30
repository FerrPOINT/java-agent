package com.azhukov.agent.core.memory;

import java.util.List;
import java.util.Map;

public interface MemoryProvider {

    List<String> recall(String userId, String query, int limit);

    void store(String userId, String category, String fact);

    // ── New two-store methods (backward-compatible defaults) ──

    default void store(String userId, String target, String category, String fact) {
        store(userId, category, fact);
    }

    default String replace(String userId, String target, String oldText, String newText) {
        throw new UnsupportedOperationException("replace not supported");
    }

    default String remove(String userId, String target, String oldText) {
        throw new UnsupportedOperationException("remove not supported");
    }

    default String read(String userId, String target) {
        List<String> facts = recall(userId, "", 100);
        return String.join("§", facts);
    }

    default Map<String, String> getSnapshot(String userId) {
        String facts = read(userId, "memory");
        return Map.of("memory", facts, "user", "");
    }

    // ── Turn lifecycle methods (backward-compatible defaults) ──

    /**
     * Called before each turn to prefetch relevant memories.
     * Default implementation is a no-op.
     *
     * @param query     the user input for this turn (may be used for semantic search)
     * @param sessionId the session identifier
     */
    default void prefetch(String query, String sessionId) {}

    /**
     * Called after a turn completes (success or error) to sync turn data.
     * Default implementation is a no-op. Implementations should make this non-blocking.
     *
     * @param sessionId    the session identifier
     * @param turnMessages the messages from the completed turn
     */
    default void syncTurn(String sessionId, List<com.azhukov.agent.core.model.Message> turnMessages) {}

    /**
     * Called when a session starts.
     * Default implementation is a no-op.
     *
     * @param sessionId the session identifier
     */
    default void onSessionStart(String sessionId) {}

    /**
     * Called when a session ends.
     * Default implementation is a no-op.
     *
     * @param sessionId the session identifier
     */
    default void onSessionEnd(String sessionId) {}
}
