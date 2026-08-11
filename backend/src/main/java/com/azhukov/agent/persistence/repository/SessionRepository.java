package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
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

    List<SessionEntity> findByTitleContainingIgnoreCase(String title);

    /**
     * Full-text search on session titles using PostgreSQL tsvector.
     * Falls back to LIKE if FTS is not available (H2).
     */
    @Query(value = "SELECT * FROM sessions WHERE title_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(title_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<SessionEntity> searchByTitleFts(@Param("q") String query);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.updatedAt = :updatedAt WHERE s.id = :id")
    void touchUpdatedAt(UUID id, Instant updatedAt);
}