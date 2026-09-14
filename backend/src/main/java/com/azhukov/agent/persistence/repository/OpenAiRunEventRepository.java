package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OpenAiRunEventRepository extends JpaRepository<OpenAiRunEventEntity, OpenAiRunEventEntity.Pk> {

    /** Replay events strictly after the cursor (exclusive), monotonic order. */
    @Query("""
        SELECT e FROM OpenAiRunEventEntity e
        WHERE e.runId = :runId AND e.seq > :afterSeq
        ORDER BY e.seq ASC
        """)
    List<OpenAiRunEventEntity> replayAfter(@Param("runId") String runId,
                                           @Param("afterSeq") long afterSeq,
                                           Pageable pageable);

    long countByRunId(String runId);

    void deleteByRunId(String runId);
}
