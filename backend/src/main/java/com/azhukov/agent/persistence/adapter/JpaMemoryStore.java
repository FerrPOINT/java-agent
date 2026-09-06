package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.MemoryStorePort;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * h12: JPA adapter implementing the core durable-memory port.
 */
@Repository
@RequiredArgsConstructor
public class JpaMemoryStore implements MemoryStorePort {

    private final MemoryRepository memoryRepository;

    @Override
    public List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target) {
        return memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target);
    }

    @Override
    public List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target, int limit) {
        return memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target,
            org.springframework.data.domain.PageRequest.of(0, limit)).getContent();
    }

    @Override
    public List<MemoryEntity> searchByUserId(String userId, String query, int limit) {
        return memoryRepository.searchByUserId(userId, query, limit);
    }

    @Override
    public MemoryEntity save(MemoryEntity entity) {
        return memoryRepository.save(entity);
    }

    @Override
    public void delete(MemoryEntity entity) {
        memoryRepository.delete(entity);
    }

    @Override
    public void deleteAll(List<MemoryEntity> entities) {
        memoryRepository.deleteAll(entities);
    }
}
