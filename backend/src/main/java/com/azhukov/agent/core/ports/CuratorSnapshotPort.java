package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port (h12): curator backup snapshot slice.
 * Implemented by the JPA {@code CuratorSnapshotRepository}.
 */
public interface CuratorSnapshotPort {

    CuratorSnapshotEntity save(CuratorSnapshotEntity entity);

    List<CuratorSnapshotEntity> findAllByOrderByCreatedAtDesc();

    Optional<CuratorSnapshotEntity> findById(UUID id);

    void deleteById(UUID id);

    void delete(CuratorSnapshotEntity entity);
}
