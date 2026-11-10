package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OpenAiRunStateRepository extends JpaRepository<OpenAiRunStateEntity, String> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OpenAiRunStateEntity r WHERE r.runId = :id")
    Optional<OpenAiRunStateEntity> lockRun(@Param("id") String id);

    Optional<OpenAiRunStateEntity> findByExternalRunIdAndUserId(String externalRunId, String userId);

    List<OpenAiRunStateEntity> findByStateInAndUpdatedAtBefore(List<String> states, Instant cutoff);
}
