package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CronJobRepository extends JpaRepository<CronJobEntity, UUID> {

    List<CronJobEntity> findByEnabledTrue();

    Page<CronJobEntity> findByEnabledTrue(Pageable pageable);

    Optional<CronJobEntity> findByName(String name);

    // ── Multi-user: userId-scoped queries ──

    /** Find cron jobs owned by a specific user (excludes global/null-userId jobs). */
    List<CronJobEntity> findByUserId(String userId);

    /** Find enabled cron jobs owned by a specific user. */
    List<CronJobEntity> findByUserIdAndEnabledTrue(String userId);

    /** Find a cron job by ID, scoped to a specific user. */
    Optional<CronJobEntity> findByIdAndUserId(UUID id, String userId);

    /** Find a cron job by name, scoped to a specific user. */
    Optional<CronJobEntity> findByNameAndUserId(String name, String userId);
}