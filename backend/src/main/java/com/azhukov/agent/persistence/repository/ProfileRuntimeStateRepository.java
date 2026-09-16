package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.ProfileRuntimeStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRuntimeStateRepository extends JpaRepository<ProfileRuntimeStateEntity, UUID> {

    Optional<ProfileRuntimeStateEntity> findByProfile(String profile);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @org.springframework.data.jpa.repository.Query("""
        update ProfileRuntimeStateEntity s
        set s.workerState = :workerState, s.updatedAt = :updatedAt
        where s.profile = :profile
        """)
    int updateWorkerState(String profile, String workerState, java.time.Instant updatedAt);
}
