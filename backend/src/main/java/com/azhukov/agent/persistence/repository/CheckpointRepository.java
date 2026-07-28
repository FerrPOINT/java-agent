package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CheckpointRepository extends JpaRepository<CheckpointEntity, UUID> {
}