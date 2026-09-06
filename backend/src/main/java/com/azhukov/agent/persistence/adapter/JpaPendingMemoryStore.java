package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.PendingMemoryStorePort;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * h12: JPA adapter implementing the core pending-memory port.
 */
@Repository
@RequiredArgsConstructor
public class JpaPendingMemoryStore implements PendingMemoryStorePort {

    private final PendingMemoryRepository pendingMemoryRepository;

    @Override
    public PendingMemoryEntity save(PendingMemoryEntity entity) {
        return pendingMemoryRepository.save(entity);
    }

    @Override
    public List<PendingMemoryEntity> findByUserIdAndStatus(String userId, String status) {
        return pendingMemoryRepository.findByUserIdAndStatus(userId, status);
    }

    @Override
    public Optional<PendingMemoryEntity> findByIdAndUserId(UUID id, String userId) {
        return pendingMemoryRepository.findByIdAndUserId(id, userId);
    }
}
