package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CompressionLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompressionLockRepository extends JpaRepository<CompressionLockEntity, UUID> {

    Optional<CompressionLockEntity> findBySessionId(UUID sessionId);
}
