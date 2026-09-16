package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CodeSessionKernelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeSessionKernelRepository extends JpaRepository<CodeSessionKernelEntity, UUID> {

    Optional<CodeSessionKernelEntity> findBySessionId(UUID sessionId);

    List<CodeSessionKernelEntity> findByStateAndExpiresAtBefore(String state, Instant cutoff);

    @Modifying
    @Query("UPDATE CodeSessionKernelEntity k SET k.state = :to, k.lastHeartbeatAt = :now "
        + "WHERE k.sessionId = :sessionId AND k.state = :from")
    int transitionState(@Param("sessionId") UUID sessionId,
                        @Param("from") String from,
                        @Param("to") String to,
                        @Param("now") Instant now);

    @Modifying
    @Query("UPDATE CodeSessionKernelEntity k SET k.lastHeartbeatAt = :now, k.expiresAt = :expiresAt "
        + "WHERE k.sessionId = :sessionId AND k.state = 'alive'")
    int heartbeat(@Param("sessionId") UUID sessionId,
                  @Param("now") Instant now,
                  @Param("expiresAt") Instant expiresAt);

    void deleteBySessionId(UUID sessionId);
}
