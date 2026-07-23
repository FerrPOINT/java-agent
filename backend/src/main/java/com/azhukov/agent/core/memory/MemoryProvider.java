package com.azhukov.agent.core.memory;

import java.util.List;

public interface MemoryProvider {

    List<String> recall(String userId, String query, int limit);

    void store(String userId, String category, String fact);
}
