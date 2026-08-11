package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.persistence.entity.CheckpointFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CheckpointFileRepository extends JpaRepository<CheckpointFileEntity, UUID> {

    List<CheckpointFileEntity> findByCheckpoint(CheckpointEntity checkpoint);

    List<CheckpointFileEntity> findByCheckpointId(UUID checkpointId);

    void deleteByCheckpointId(UUID checkpointId);
}