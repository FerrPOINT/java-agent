package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.PendingMemoryEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port (h12): pending (write-approval) memory slice.
 * Implemented by the JPA {@code PendingMemoryRepository}.
 */
public interface PendingMemoryStorePort {

    PendingMemoryEntity save(PendingMemoryEntity entity);

    List<PendingMemoryEntity> findByUserIdAndStatus(String userId, String status);

    Optional<PendingMemoryEntity> findByIdAndUserId(UUID id, String userId);
}
