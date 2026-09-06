package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.MemoryEntity;

import java.util.List;

/**
 * Persistence port (h12): durable-memory slice used by the agent core.
 * Implemented by the JPA {@code MemoryRepository}.
 */
public interface MemoryStorePort {

    List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target);

    List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target, int limit);

    List<MemoryEntity> searchByUserId(String userId, String query, int limit);

    MemoryEntity save(MemoryEntity entity);

    void delete(MemoryEntity entity);

    void deleteAll(List<MemoryEntity> entities);
}
