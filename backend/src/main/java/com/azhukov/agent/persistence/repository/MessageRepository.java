package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    Page<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId, Pageable pageable);

    List<MessageEntity> findBySessionIdAndTurnIndexOrderByCreatedAtAsc(UUID sessionId, Integer turnIndex);

    long countBySessionId(UUID sessionId);

    List<MessageEntity> findByContentContainingIgnoreCase(String content);

    /**
     * Loads the last N messages for a session in descending order (newest first).
     * The caller should reverse the result to get ascending order.
     *
     * @param sessionId the session ID
     * @param pageable  pagination (page 0, size N gives the first N = newest N)
     * @return the last N messages in descending order
     */
    List<MessageEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    /**
     * Load only active (non-archived) messages for a session, ordered by creation time.
     * Mirrors Hermes get_messages(session_id) which filters active=1 by default.
     */
    List<MessageEntity> findBySessionIdAndActiveTrueOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Full-text search on message content using PostgreSQL tsvector.
     * Returns messages ranked by FTS relevance, limited to :limit results (H13).
     */
    @Query(value = "SELECT * FROM messages WHERE content_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :q)) DESC LIMIT :limit",
           nativeQuery = true)
    List<MessageEntity> searchByContentFts(@Param("q") String query, @Param("limit") int limit);

    /**
     * Full-text search on message content excluding hidden session sources.
     * Mirrors Hermes search_messages(exclude_sources=...).
     */
    @Query(value = "SELECT m.* FROM messages m " +
                   "JOIN sessions s ON m.session_id = s.id " +
                   "WHERE m.content_tsv @@ plainto_tsquery('english', :q) " +
                   "AND (s.source IS NULL OR s.source NOT IN :excludedSources) " +
                   "ORDER BY ts_rank(m.content_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<MessageEntity> searchByContentFtsExcludingSources(@Param("q") String query, @Param("excludedSources") List<String> excludedSources);

    /**
     * Find messages by session_id and role, ordered by creation time.
     * Used for role-filtered discovery.
     */
    List<MessageEntity> findBySessionIdAndRoleInOrderByCreatedAtAsc(UUID sessionId, List<String> roles);

    /**
     * Get messages around a specific message ID (anchor), returning a window of ±windowSize messages.
     * Mirrors Hermes get_messages_around(session_id, around_message_id, window).
     * Returns all messages from the session ordered by creation time; the caller
     * computes the window position in Java.
     */
    default List<MessageEntity> findAllBySessionIdOrdered(UUID sessionId) {
        return findBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}