package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CronExecutionLogRepository extends JpaRepository<CronExecutionLogEntity, Long> {

    java.util.Optional<CronExecutionLogEntity> findFirstByJobIdOrderByStartedAtDesc(UUID jobId);

    List<CronExecutionLogEntity> findByJobIdOrderByStartedAtDesc(UUID jobId);
}