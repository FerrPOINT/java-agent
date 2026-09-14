package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.ConsoleTaskOutputEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsoleTaskOutputRepository extends JpaRepository<ConsoleTaskOutputEntity, ConsoleTaskOutputEntity.Pk> {

    /** Replay redacted lines strictly after the client cursor, monotonic order. */
    @Query("SELECT o FROM ConsoleTaskOutputEntity o "
        + "WHERE o.taskId = :taskId AND o.sequence > :after "
        + "ORDER BY o.sequence ASC")
    List<ConsoleTaskOutputEntity> replayAfter(@Param("taskId") String taskId,
                                              @Param("after") long after,
                                              Pageable pageable);

    Optional<ConsoleTaskOutputEntity> findFirstByTaskIdOrderBySequenceDesc(String taskId);

    long countByTaskId(String taskId);
}
