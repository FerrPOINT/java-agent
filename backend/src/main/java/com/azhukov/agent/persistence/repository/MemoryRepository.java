package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, UUID> {

    List<MemoryEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    // H13: Bounded overload — avoids loading an unbounded result set for listing.
    Page<MemoryEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    @Query(value = "SELECT * FROM memory WHERE user_id = ?1 AND to_tsvector('russian', fact || ' ' || COALESCE(category,'')) @@ plainto_tsquery('russian', ?2) ORDER BY created_at DESC LIMIT ?3", nativeQuery = true)
    List<MemoryEntity> searchByUserId(String userId, String query, int limit);

    List<MemoryEntity> findByUserIdAndFactLikeIgnoreCase(String userId, String fact);

    List<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target);

    // H13: Bounded overload — avoids loading an unbounded result set when only a page is needed.
    Page<MemoryEntity> findByUserIdAndTargetOrderByCreatedAtDesc(String userId, String target, Pageable pageable);

    List<MemoryEntity> findByUserIdAndTargetAndFactContaining(String userId, String target, String fact);

    // M24: Exact match for replace/remove to prevent unintended broad edits
    List<MemoryEntity> findByUserIdAndTargetAndFact(String userId, String target, String fact);

    void deleteByUserIdAndTargetAndFactContaining(String userId, String target, String fact);
}
