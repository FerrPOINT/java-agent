package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CuratorSnapshotRepository extends JpaRepository<CuratorSnapshotEntity, UUID> {

    List<CuratorSnapshotEntity> findAllByOrderByCreatedAtDesc();
}