package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, UUID> {

    List<MemoryEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query(value = "SELECT * FROM memory WHERE user_id = ?1 AND to_tsvector('russian', fact || ' ' || COALESCE(category,'')) @@ plainto_tsquery('russian', ?2) ORDER BY created_at DESC LIMIT ?3", nativeQuery = true)
    List<MemoryEntity> searchByUserId(String userId, String query, int limit);

    List<MemoryEntity> findByUserIdAndFactLikeIgnoreCase(String userId, String fact);

    List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target);

    List<MemoryEntity> findByUserIdAndTargetAndFactContaining(String userId, String target, String fact);

    void deleteByUserIdAndTargetAndFactContaining(String userId, String target, String fact);
}
