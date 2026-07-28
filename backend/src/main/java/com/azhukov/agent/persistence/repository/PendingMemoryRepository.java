package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingMemoryRepository extends JpaRepository<PendingMemoryEntity, UUID> {

    List<PendingMemoryEntity> findByUserIdAndStatus(String userId, String status);

    Optional<PendingMemoryEntity> findByIdAndUserId(UUID id, String userId);
}