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

    /** rev-89 ownership: jobs owned by a user. */
    java.util.List<CronJobEntity> findByUserId(String userId);


    java.util.Optional<CronJobEntity> findByIdAndUserId(java.util.UUID id, String userId);

}
