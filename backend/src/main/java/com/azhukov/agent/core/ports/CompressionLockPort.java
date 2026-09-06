package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.CompressionLockEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port (h12): context-compression lock slice.
 * Implemented by the JPA {@code CompressionLockRepository}.
 */
public interface CompressionLockPort {

    Optional<CompressionLockEntity> findBySessionId(UUID sessionId);

    CompressionLockEntity save(CompressionLockEntity entity);
}
