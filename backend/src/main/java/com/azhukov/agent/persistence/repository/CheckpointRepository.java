package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CheckpointEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CheckpointRepository extends JpaRepository<CheckpointEntity, UUID> {

    // ── Multi-user: userId-scoped queries ──

    /** Find checkpoints owned by a specific user. */
    List<CheckpointEntity> findByUserId(String userId);

    List<CheckpointEntity> findByUserId(String userId, Sort sort);

    /** Find a checkpoint by ID, scoped to a specific user. */
    Optional<CheckpointEntity> findByIdAndUserId(UUID id, String userId);
}