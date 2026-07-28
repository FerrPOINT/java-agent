package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CronJobRepository extends JpaRepository<CronJobEntity, UUID> {

    List<CronJobEntity> findByEnabledTrue();

    Optional<CronJobEntity> findByName(String name);
}