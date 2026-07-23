package com.azhukov.agent.core.memory;

import java.util.List;

public class NoOpMemoryProvider implements MemoryProvider {

    @Override
    public List<String> recall(String userId, String query, int limit) {
        return List.of();
    }

    @Override
    public void store(String userId, String category, String fact) {
        // no-op
    }
}
