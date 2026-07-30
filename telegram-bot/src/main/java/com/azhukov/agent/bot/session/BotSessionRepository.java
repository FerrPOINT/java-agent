package com.azhukov.agent.bot.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BotSessionRepository extends JpaRepository<BotSessionEntity, UUID> {

    Optional<BotSessionEntity> findByUserIdAndActiveTrue(String userId);

    Optional<BotSessionEntity> findByChatIdAndActiveTrue(String chatId);

    List<BotSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    // P0: Session expiry watcher — list all active sessions
    List<BotSessionEntity> findByActiveTrue();

    @Modifying
    @Query("UPDATE BotSessionEntity s SET s.updatedAt = :ts WHERE s.id = :id")
    void touchUpdatedAt(UUID id, Instant ts);

    @Modifying
    @Query("UPDATE BotSessionEntity s SET s.active = false WHERE s.userId = :userId AND s.active = true")
    int deactivateAllForUser(String userId);
}