package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CronSuggestionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CronSuggestionRepository extends JpaRepository<CronSuggestionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select suggestion from CronSuggestionEntity suggestion where suggestion.id = :id and suggestion.userId = :userId")
    Optional<CronSuggestionEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") String userId);

    Optional<CronSuggestionEntity> findByIdAndUserId(UUID id, String userId);

    Optional<CronSuggestionEntity> findByUserIdAndDedupKey(String userId, String dedupKey);

    List<CronSuggestionEntity> findByUserIdAndStatusOrderByCreatedAtAsc(String userId, String status);

    List<CronSuggestionEntity> findByUserIdOrderByCreatedAtAsc(String userId);

    long countByUserIdAndStatus(String userId, String status);
}
