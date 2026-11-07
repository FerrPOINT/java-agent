package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CronJobRepository extends JpaRepository<CronJobEntity, UUID> {

    List<CronJobEntity> findByEnabledTrue();

    List<CronJobEntity> findByEnabledTrue(Sort sort);

    Page<CronJobEntity> findByEnabledTrue(Pageable pageable);

    List<CronJobEntity> findByProfile(String profile, Sort sort);

    List<CronJobEntity> findByProfileAndEnabledTrue(String profile, Sort sort);

    Optional<CronJobEntity> findByName(String name);

    Optional<CronJobEntity> findByNameAndProfile(String name, String profile);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from CronJobEntity j where j.id = :id")
    Optional<CronJobEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("""
        update CronJobEntity j set
            j.lastRunAt = :#{#run.lastRunAt},
            j.lastStatus = :#{#run.lastStatus},
            j.lastError = :#{#run.lastError},
            j.lastErrorAt = :#{#run.lastErrorAt},
            j.consecutiveFailures = :#{#run.consecutiveFailures},
            j.unreachableRetries = :#{#run.unreachableRetries},
            j.lastRunSessionId = :#{#run.lastRunSessionId},
            j.monitorLastHash = :#{#run.monitorLastHash},
            j.monitorLastOutput = :#{#run.monitorLastOutput},
            j.monitorLastChangedAt = :#{#run.monitorLastChangedAt}
        where j.id = :#{#run.id}
          and j.nextRunAt is not distinct from :#{#run.nextRunAt}
        """)
    int recordRun(@org.springframework.data.repository.query.Param("run") CronJobEntity run);

    /** rev-89 ownership: jobs owned by a user. */
    java.util.List<CronJobEntity> findByUserId(String userId);


    java.util.Optional<CronJobEntity> findByIdAndUserId(java.util.UUID id, String userId);

}
