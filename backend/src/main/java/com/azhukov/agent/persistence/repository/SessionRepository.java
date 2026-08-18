package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
    SessionEntity findByUserId(String userId);

    List<SessionEntity> findAllByUserId(String userId);

    // M15: Count query for reliable has_more pagination
    long countByUserId(String userId);

    Page<SessionEntity> findAllByUserId(String userId, Pageable pageable);

    List<SessionEntity> findByTitleContainingIgnoreCase(String title);

    /**
     * Find child sessions by parent_session_id, ordered by most recently created.
     * Used for compression child-chain resolution (parity with Hermes resolve_resume_session_id).
     */
    List<SessionEntity> findByParentSessionIdOrderByCreatedAtDesc(UUID parentSessionId);

    /**
     * List recent sessions excluding hidden sources, ordered by last_active desc.
     * Mirrors Hermes list_sessions_rich(exclude_sources=..., order_by_last_active=True).
     */
    @Query("SELECT s FROM SessionEntity s WHERE (s.source IS NULL OR s.source NOT IN :excludedSources) ORDER BY s.lastActive DESC NULLS LAST, s.updatedAt DESC")
    List<SessionEntity> listRecentExcludingSources(@Param("excludedSources") List<String> excludedSources, Pageable pageable);

    /**
     * Full-text search on session titles using PostgreSQL tsvector.
     * Falls back to LIKE if FTS is not available (H2).
     */
    @Query(value = "SELECT * FROM sessions WHERE title_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(title_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<SessionEntity> searchByTitleFts(@Param("q") String query);

    /**
     * Full-text search on session titles excluding hidden sources.
     */
    @Query(value = "SELECT * FROM sessions WHERE title_tsv @@ plainto_tsquery('english', :q) " +
                   "AND (source IS NULL OR source NOT IN :excludedSources) " +
                   "ORDER BY ts_rank(title_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<SessionEntity> searchByTitleFtsExcludingSources(@Param("q") String query, @Param("excludedSources") List<String> excludedSources);

    /**
     * Find session by title (exact match, case-insensitive) — for title-match discovery.
     */
    SessionEntity findByTitleIgnoreCase(String title);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.updatedAt = :updatedAt WHERE s.id = :id")
    void touchUpdatedAt(UUID id, Instant updatedAt);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.lastActive = :lastActive, s.messageCount = :messageCount WHERE s.id = :id")
    void updateLastActiveAndMessageCount(UUID id, Instant lastActive, int messageCount);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.preview = :preview WHERE s.id = :id")
    void updatePreview(UUID id, String preview);
}