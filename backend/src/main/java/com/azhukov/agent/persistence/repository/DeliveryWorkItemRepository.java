package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryWorkItemRepository extends JpaRepository<DeliveryWorkItemEntity, UUID> {

    Optional<DeliveryWorkItemEntity> findBySourceTypeAndSourceIdAndTargetHash(
        String sourceType, String sourceId, String targetHash);

    List<DeliveryWorkItemEntity> findByStateAndClaimedAtBefore(String state, Instant cutoff);

    @Query("""
        select item from DeliveryWorkItemEntity item
        where item.state = 'pending'
          and item.availableAt <= :now
          and item.profile in :profiles
        order by item.createdAt asc
        """)
    List<DeliveryWorkItemEntity> findClaimable(
        @Param("profiles") List<String> profiles,
        @Param("now") Instant now,
        Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("""
        update DeliveryWorkItemEntity item
        set item.state = 'claimed',
            item.claimToken = :claimToken,
            item.claimedAt = :claimedAt,
            item.attempts = item.attempts + 1,
            item.errorCategory = null,
            item.errorDetail = null
        where item.id = :id
          and item.state = 'pending'
          and item.availableAt <= :claimedAt
        """)
    int claimPending(
        @Param("id") UUID id,
        @Param("claimToken") String claimToken,
        @Param("claimedAt") Instant claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("""
        update DeliveryWorkItemEntity item
        set item.state = 'delivered',
            item.deliveredAt = :deliveredAt,
            item.outboundMessageId = :outboundMessageId,
            item.idempotencyKey = :idempotencyKey,
            item.claimToken = null,
            item.claimedAt = null
        where item.id = :id
          and item.state = 'claimed'
          and item.claimToken = :claimToken
        """)
    int markDelivered(
        @Param("id") UUID id,
        @Param("claimToken") String claimToken,
        @Param("deliveredAt") Instant deliveredAt,
        @Param("outboundMessageId") String outboundMessageId,
        @Param("idempotencyKey") String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("""
        update DeliveryWorkItemEntity item
        set item.state = 'pending',
            item.availableAt = :availableAt,
            item.claimToken = null,
            item.claimedAt = null,
            item.errorCategory = :errorCategory,
            item.errorDetail = :errorDetail
        where item.id = :id
          and item.state = 'claimed'
          and item.claimToken = :claimToken
        """)
    int releaseKnownFailure(
        @Param("id") UUID id,
        @Param("claimToken") String claimToken,
        @Param("availableAt") Instant availableAt,
        @Param("errorCategory") String errorCategory,
        @Param("errorDetail") String errorDetail);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("""
        update DeliveryWorkItemEntity item
        set item.state = :terminalState,
            item.claimToken = null,
            item.claimedAt = null,
            item.errorCategory = :errorCategory,
            item.errorDetail = :errorDetail,
            item.droppedAt = case when :terminalState = 'dropped' then :terminalAt else item.droppedAt end,
            item.unknownAt = case when :terminalState = 'unknown' then :terminalAt else item.unknownAt end
        where item.id = :id
          and item.state = 'claimed'
          and item.claimToken = :claimToken
        """)
    int markTerminal(
        @Param("id") UUID id,
        @Param("claimToken") String claimToken,
        @Param("terminalState") String terminalState,
        @Param("terminalAt") Instant terminalAt,
        @Param("errorCategory") String errorCategory,
        @Param("errorDetail") String errorDetail);
}
