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
}
