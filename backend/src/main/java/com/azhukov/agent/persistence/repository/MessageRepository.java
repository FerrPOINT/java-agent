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
     * Full-text search on message content using PostgreSQL tsvector.
     * Returns messages ranked by FTS relevance.
     */
    @Query(value = "SELECT * FROM messages WHERE content_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<MessageEntity> searchByContentFts(@Param("q") String query);
}