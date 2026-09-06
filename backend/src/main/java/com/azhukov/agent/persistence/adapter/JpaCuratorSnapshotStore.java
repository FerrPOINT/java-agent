package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.CuratorSnapshotPort;
import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import com.azhukov.agent.persistence.repository.CuratorSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * h12: JPA adapter implementing the core curator-snapshot port.
 */
@Repository
@RequiredArgsConstructor
public class JpaCuratorSnapshotStore implements CuratorSnapshotPort {

    private final CuratorSnapshotRepository curatorSnapshotRepository;

    @Override
    public CuratorSnapshotEntity save(CuratorSnapshotEntity entity) {
        return curatorSnapshotRepository.save(entity);
    }

    @Override
    public List<CuratorSnapshotEntity> findAllByOrderByCreatedAtDesc() {
        return curatorSnapshotRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public Optional<CuratorSnapshotEntity> findById(UUID id) {
        return curatorSnapshotRepository.findById(id);
    }

    @Override
    public void deleteById(UUID id) {
        curatorSnapshotRepository.deleteById(id);
    }

    @Override
    public void delete(CuratorSnapshotEntity entity) {
        curatorSnapshotRepository.delete(entity);
    }
}
