package com.azhukov.agent.bot.session;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists chat-scoped bot state so recovered deliveries retain the backend session identity.
 */
@Repository
public interface BotSessionRepository extends JpaRepository<BotSessionEntity, UUID> {

    Optional<BotSessionEntity> findByUserIdAndActiveTrue(String userId);

    /**
     * V9: active session for a user inside a specific forum topic.
     * threadId null = the DM / non-topic lane.
     */
    Optional<BotSessionEntity> findByUserIdAndThreadIdAndActiveTrue(String userId, Long threadId);

    List<BotSessionEntity> findByUserIdAndThreadId(String userId, Long threadId);

    Page<BotSessionEntity> findByUserIdAndActiveTrue(String userId, Pageable pageable);

    Optional<BotSessionEntity> findByChatIdAndActiveTrue(String chatId);

    Page<BotSessionEntity> findByChatIdAndActiveTrue(String chatId, Pageable pageable);

    List<BotSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<BotSessionEntity> findByBackendSessionIdIsNotNull();

    // P0: Session expiry watcher — list all active sessions
    List<BotSessionEntity> findByActiveTrue();

    List<BotSessionEntity> findByCleanupPendingTrueAndActiveFalse();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BotSessionEntity s WHERE s.userId = :userId ORDER BY s.id")
    List<BotSessionEntity> findAllLockedByUserId(String userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BotSessionEntity s WHERE s.id = :id")
    Optional<BotSessionEntity> findLockedById(UUID id);

    /** Active, non-suspended sessions — candidates for resume-pending marking on shutdown. */
    List<BotSessionEntity> findByActiveTrueAndSuspendedFalse();

    /** Sessions interrupted by a restart that still need user notification. */
    List<BotSessionEntity> findByResumePendingTrueAndActiveTrue();

    @Modifying
    @Query("UPDATE BotSessionEntity s SET s.updatedAt = :ts WHERE s.id = :id")
    void touchUpdatedAt(UUID id, Instant ts);

    @Modifying
    @Query("UPDATE BotSessionEntity s SET s.active = false, s.cleanupOnBackendBinding = true, s.cleanupPending = CASE WHEN s.backendSessionId IS NULL THEN false ELSE true END WHERE s.userId = :userId AND s.active = true")
    int deactivateAllForUserWithPendingCleanup(String userId);

    @Modifying
    @Query("UPDATE BotSessionEntity s SET s.active = false WHERE s.userId = :userId AND s.active = true")
    int deactivateAllForUser(String userId);
}