package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DelegatedTaskRunRepository extends JpaRepository<DelegatedTaskRunEntity, UUID> {

    List<DelegatedTaskRunEntity> findByParentSessionIdOrderByCreatedAtDesc(UUID parentSessionId, Pageable pageable);

    @Query("""
        select run
        from DelegatedTaskRunEntity run
        where run.parentSessionId = :parentSessionId
          and run.completedAt is not null
          and run.deliveredAt is null
          and run.deliveryDroppedAt is null
        order by run.completedAt asc
        """)
    List<DelegatedTaskRunEntity> findByParentSessionIdAndCompletedAtIsNotNullAndDeliveredAtIsNullOrderByCompletedAtAsc(
        @Param("parentSessionId") UUID parentSessionId,
        Pageable pageable);

    @Query("""
        select run
        from DelegatedTaskRunEntity run
        where run.completedAt is not null
          and run.deliveredAt is null
          and run.deliveryDroppedAt is null
          and (
            run.deliveryClaim is null
            or run.deliveryClaimedAt is null
            or run.deliveryClaimedAt < :staleBefore
          )
        order by run.completedAt asc
        """)
    List<DelegatedTaskRunEntity> findRestorablePendingDelivery(
        @Param("staleBefore") Instant staleBefore,
        Pageable pageable);

    @Query("""
        select run
        from DelegatedTaskRunEntity run
        where run.parentSessionId = :parentSessionId
          and run.completedAt is not null
          and run.deliveredAt is null
          and run.deliveryDroppedAt is null
          and (
            (:profile = 'default' and (run.profile is null or run.profile = 'default'))
            or (:profile <> 'default' and run.profile = :profile)
          )
        order by run.completedAt asc
        """)
    List<DelegatedTaskRunEntity> findPendingDeliveryForProfile(
        @Param("parentSessionId") UUID parentSessionId,
        @Param("profile") String profile,
        Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DelegatedTaskRunEntity run
        set run.deliveryClaim = :claimId,
            run.deliveryClaimedAt = :claimedAt,
            run.deliveryAttempts = run.deliveryAttempts + 1,
            run.deliveryError = null
        where run.id = :runId
          and run.completedAt is not null
          and run.deliveredAt is null
          and run.deliveryDroppedAt is null
          and (
            run.deliveryClaim is null
            or run.deliveryClaimedAt is null
            or run.deliveryClaimedAt < :staleBefore
          )
        """)
    int claimPendingDelivery(
        @Param("runId") UUID runId,
        @Param("claimId") String claimId,
        @Param("claimedAt") Instant claimedAt,
        @Param("staleBefore") Instant staleBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DelegatedTaskRunEntity run
        set run.deliveredAt = :deliveredAt,
            run.deliveryTarget = :target,
            run.deliveryIdempotencyKey = :idempotencyKey,
            run.deliveryError = null,
            run.deliveryClaim = null,
            run.deliveryClaimedAt = null
        where run.id = :runId
          and run.completedAt is not null
          and run.deliveredAt is null
          and run.deliveryDroppedAt is null
          and run.deliveryClaim = :claimId
        """)
    int completeDeliveryClaim(
        @Param("runId") UUID runId,
        @Param("claimId") String claimId,
        @Param("target") String target,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("deliveredAt") Instant deliveredAt);

    long countByParentSessionIdAndStatusIn(UUID parentSessionId, Collection<String> statuses);
}
