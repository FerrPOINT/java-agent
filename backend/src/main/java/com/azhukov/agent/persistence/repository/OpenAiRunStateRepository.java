package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OpenAiRunStateRepository extends JpaRepository<OpenAiRunStateEntity, String> {

    Optional<OpenAiRunStateEntity> findByExternalRunIdAndUserId(String externalRunId, String userId);

    List<OpenAiRunStateEntity> findByStateInAndUpdatedAtBefore(List<String> states, Instant cutoff);

    /**
     * Atomic state transition guarded by the expected current state:
     * returns 0 when the run moved concurrently (lost race) — callers
     * re-read instead of guessing.
     */
    @Modifying
    @Query("""
        UPDATE OpenAiRunStateEntity r
        SET r.state = :to, r.updatedAt = :now,
            r.stateReason = COALESCE(:reason, r.stateReason),
            r.cancelReason = COALESCE(:cancelReason, r.cancelReason),
            r.cancelledAt = COALESCE(:cancelledAt, r.cancelledAt),
            r.lastSeq = COALESCE(:lastSeq, r.lastSeq)
        WHERE r.runId = :runId AND r.state = :from
        """)
    int transition(@Param("runId") String runId,
                   @Param("from") String from,
                   @Param("to") String to,
                   @Param("reason") String reason,
                   @Param("cancelReason") String cancelReason,
                   @Param("cancelledAt") Instant cancelledAt,
                   @Param("lastSeq") Long lastSeq,
                   @Param("now") Instant now);
}
