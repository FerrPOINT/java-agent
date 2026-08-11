package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<MessageEntity> findBySessionIdAndTurnIndexOrderByCreatedAtAsc(UUID sessionId, Integer turnIndex);

    long countBySessionId(UUID sessionId);

    List<MessageEntity> findByContentContainingIgnoreCase(String content);

    /**
     * Full-text search on message content using PostgreSQL tsvector.
     * Returns messages ranked by FTS relevance.
     */
    @Query(value = "SELECT * FROM messages WHERE content_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<MessageEntity> searchByContentFts(@Param("q") String query);
}
