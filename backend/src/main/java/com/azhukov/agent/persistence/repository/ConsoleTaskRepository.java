package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.ConsoleTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConsoleTaskRepository extends JpaRepository<ConsoleTaskEntity, String> {

    Optional<ConsoleTaskEntity> findByIdAndProfile(String id, String profile);

    List<ConsoleTaskEntity> findByStateAndExpiresAtBefore(String state, Instant cutoff);

    /** Atomic guarded terminal transition; loser gets 0. */
    @Modifying
    @Query("UPDATE ConsoleTaskEntity t SET t.state = :to, t.finishedAt = :now, "
        + "t.exitCode = COALESCE(:exitCode, t.exitCode) "
        + "WHERE t.id = :id AND t.state = 'running'")
    int finishTask(@Param("id") String id,
                   @Param("to") String to,
                   @Param("exitCode") Integer exitCode,
                   @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM ConsoleTaskEntity t WHERE t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
