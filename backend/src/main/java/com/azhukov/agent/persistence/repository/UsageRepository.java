package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.UsageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UsageRepository extends JpaRepository<UsageEntity, UUID> {

    List<UsageEntity> findBySessionId(UUID sessionId);

    List<UsageEntity> findByUserIdAndCreatedAtBetween(String userId, Instant start, Instant end);

    Page<UsageEntity> findByUserIdAndCreatedAtBetween(String userId, Instant start, Instant end, Pageable pageable);
}