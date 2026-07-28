package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class CronJobService {

    private final CronJobRepository cronJobRepository;
    private final ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    private final AgentProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final Map<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @PostConstruct
    public void init() {
        if (!properties.getCron().isEnabled()) {
            log.info("Cron jobs disabled by configuration");
            return;
        }
        log.info("Initializing cron jobs...");
        List<CronJobEntity> jobs = cronJobRepository.findByEnabledTrue();
        for (CronJobEntity job : jobs) {
            scheduleJob(job);
        }
        log.info("Loaded {} cron jobs", jobs.size());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down cron scheduler...");
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo) {
        validateCronExpression(schedule);
        CronJobEntity entity = new CronJobEntity();
        entity.setName(name);
        entity.setSchedule(schedule);
        entity.setPrompt(prompt);
        entity.setDeliverTo(deliverTo);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());
        entity = cronJobRepository.save(entity);
        if (properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Created cron job: {} (schedule: {})", name, schedule);
        return entity;
    }

    public List<CronJobEntity> list() {
        return cronJobRepository.findAll();
    }

    public CronJobEntity update(UUID id, String name, String schedule, String prompt, String deliverTo, Boolean enabled) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        if (name != null) entity.setName(name);
        if (schedule != null) {
            validateCronExpression(schedule);
            entity.setSchedule(schedule);
        }
        if (prompt != null) entity.setPrompt(prompt);
        if (deliverTo != null) entity.setDeliverTo(deliverTo);
        if (enabled != null) entity.setEnabled(enabled);
        entity = cronJobRepository.save(entity);
        // Reschedule
        cancelJob(id);
        if (entity.isEnabled() && properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Updated cron job: {}", id);
        return entity;
    }

    public CronJobEntity pause(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        entity.setEnabled(false);
        entity = cronJobRepository.save(entity);
        cancelJob(id);
        log.info("Paused cron job: {}", id);
        return entity;
    }

    public CronJobEntity resume(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        entity.setEnabled(true);
        entity = cronJobRepository.save(entity);
        if (properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Resumed cron job: {}", id);
        return entity;
    }

    public void remove(UUID id) {
        cancelJob(id);
        cronJobRepository.deleteById(id);
        log.info("Removed cron job: {}", id);
    }

    public CronJobEntity runNow(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        executeJob(entity);
        return entity;
    }

    public Optional<CronJobEntity> findByName(String name) {
        return cronJobRepository.findByName(name);
    }

    private void scheduleJob(CronJobEntity job) {
        try {
            long delaySeconds = calculateDelaySeconds(job.getSchedule());
            ScheduledFuture<?> future = scheduler.schedule(
                () -> executeAndReschedule(job.getId()),
                delaySeconds, TimeUnit.SECONDS
            );
            scheduledTasks.put(job.getId(), future);
            job.setNextRunAt(Instant.now().plusSeconds(delaySeconds));
            cronJobRepository.save(job);
            log.debug("Scheduled cron job '{}' to run in {} seconds", job.getName(), delaySeconds);
        } catch (Exception e) {
            log.error("Failed to schedule cron job {}: {}", job.getName(), e.getMessage());
        }
    }

    private void executeAndReschedule(UUID jobId) {
        try {
            CronJobEntity job = cronJobRepository.findById(jobId).orElse(null);
            if (job == null || !job.isEnabled()) return;
            executeJob(job);
            // Reschedule
            scheduleJob(job);
        } catch (Exception e) {
            log.error("Error executing cron job {}: {}", jobId, e.getMessage());
        }
    }

    private void executeJob(CronJobEntity job) {
        log.info("Executing cron job: {} (deliverTo: {})", job.getName(), job.getDeliverTo());
        try {
            if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                // Deliver to platform — could be extended for telegram, discord, etc.
                log.info("Delivering cron job '{}' output to: {}", job.getName(), job.getDeliverTo());
            }
            // Run the prompt through the agent runtime
            AgentRuntimeService runtimeService = agentRuntimeServiceProvider.getIfAvailable();
            if (runtimeService != null) {
                runtimeService.runBackground(job.getPrompt(), null);
            } else {
                log.warn("AgentRuntimeService not available, skipping cron job execution: {}", job.getName());
            }
            job.setLastRunAt(Instant.now());
            cronJobRepository.save(job);
        } catch (Exception e) {
            log.error("Failed to execute cron job {}: {}", job.getName(), e.getMessage());
        }
    }

    private void cancelJob(UUID id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    private long calculateDelaySeconds(String cronExpression) {
        try {
            Cron cron = cronParser.parse(cronExpression);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            java.util.Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(now);
            if (nextExecution.isPresent()) {
                long delaySeconds = java.time.Duration.between(now, nextExecution.get()).getSeconds();
                return Math.max(1, delaySeconds);
            }
            return 60; // fallback
        } catch (Exception e) {
            log.warn("Failed to parse cron expression '{}', using default 60s delay: {}", cronExpression, e.getMessage());
            return 60;
        }
    }

    private void validateCronExpression(String schedule) {
        try {
            cronParser.parse(schedule);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + schedule + " — " + e.getMessage());
        }
    }
}