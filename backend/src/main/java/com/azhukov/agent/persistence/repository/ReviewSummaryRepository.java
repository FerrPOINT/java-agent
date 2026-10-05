package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.ReviewSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewSummaryRepository extends JpaRepository<ReviewSummaryEntity, UUID> {

    List<ReviewSummaryEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    Optional<ReviewSummaryEntity> findFirstBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    @Modifying
    @Transactional
    int deleteBySessionId(UUID sessionId);

    @Modifying
    @Transactional
    @Query("delete from ReviewSummaryEntity r where r.sessionId = :sessionId and r.deliveryId = :deliveryId")
    int deleteBySessionIdAndDeliveryId(@Param("sessionId") UUID sessionId,
                                       @Param("deliveryId") UUID deliveryId);
}
