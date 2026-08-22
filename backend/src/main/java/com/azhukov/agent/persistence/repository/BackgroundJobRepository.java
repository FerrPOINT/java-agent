package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.BackgroundJobEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJobEntity, UUID> {

    @Modifying
    @Transactional
    @Query("UPDATE BackgroundJobEntity j SET j.status = :status WHERE j.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("UPDATE BackgroundJobEntity j SET j.status = :status, j.result = :result, j.finishedAt = :finishedAt WHERE j.id = :id")
    void finish(@Param("id") UUID id, @Param("status") String status, @Param("result") String result,
                @Param("finishedAt") java.time.Instant finishedAt);

    Optional<BackgroundJobEntity> findBySessionId(UUID sessionId);
}
