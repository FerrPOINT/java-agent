package com.azhukov.agent.core.memory;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;

import java.time.Instant;
import java.util.List;

public class DatabaseMemoryProvider implements MemoryProvider {

    private final MemoryRepository memoryRepository;

    public DatabaseMemoryProvider(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public List<String> recall(String userId, String query, int limit) {
        return memoryRepository.searchByUserId(userId, query, limit).stream()
            .map(e -> "[" + e.getCategory() + "] " + e.getFact())
            .toList();
    }

    @Override
    public void store(String userId, String category, String fact) {
        MemoryEntity e = new MemoryEntity();
        e.setUserId(userId);
        e.setCategory(category);
        e.setFact(fact);
        e.setCreatedAt(Instant.now());
        memoryRepository.save(e);
    }
}
