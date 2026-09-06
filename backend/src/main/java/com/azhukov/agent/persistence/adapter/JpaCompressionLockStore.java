package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.CompressionLockPort;
import com.azhukov.agent.persistence.entity.CompressionLockEntity;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * h12: JPA adapter implementing the core compression-lock port.
 */
@Repository
@RequiredArgsConstructor
public class JpaCompressionLockStore implements CompressionLockPort {

    private final CompressionLockRepository compressionLockRepository;

    @Override
    public Optional<CompressionLockEntity> findBySessionId(UUID sessionId) {
        return compressionLockRepository.findBySessionId(sessionId);
    }

    @Override
    public CompressionLockEntity save(CompressionLockEntity entity) {
        return compressionLockRepository.save(entity);
    }
}
