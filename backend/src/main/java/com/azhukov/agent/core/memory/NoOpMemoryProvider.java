package com.azhukov.agent.core.memory;

import java.util.List;

public class NoOpMemoryProvider implements MemoryProvider {

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<String> recall(String userId, String query, int limit) {
        return List.of();
    }

    @Override
    public void store(String userId, String category, String fact) {
        // no-op
    }

    @Override
    public void store(String userId, String target, String category, String fact) {
        // no-op
    }

    @Override
    public String replace(String userId, String target, String oldText, String newText) {
        return null;
    }

    @Override
    public String remove(String userId, String target, String oldText) {
        return null;
    }

    @Override
    public int clear(String userId, String target) {
        return 0;
    }

    @Override
    public String read(String userId, String target) {
        return "";
    }

    @Override
    public java.util.Map<String, String> getSnapshot(String userId) {
        return java.util.Map.of("memory", "", "user", "");
    }
}
