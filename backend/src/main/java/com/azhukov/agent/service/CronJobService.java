package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
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
import org.springframework.data.domain.PageRequest;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
@RequiredArgsConstructor
public class CronJobService {

    private final CronJobRepository cronJobRepository;
    private final ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    private final AgentProperties properties;
    private final SkillManager skillManager;

    // Daemon thread factory so cron threads don't prevent JVM shutdown
    private static final ThreadFactory DAEMON_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "cron-scheduler");
        t.setDaemon(true);
        return t;
    };

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10, DAEMON_THREAD_FACTORY);
    private final Map<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    // Per-job lock to prevent concurrent execution of the same job
    private final Map<UUID, ReentrantLock> jobLocks = new ConcurrentHashMap<>();
    private final CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @PostConstruct
    public void init() {
        if (!properties.getCron().isEnabled()) {
            log.info("Cron jobs disabled by configuration");
            return;
        }
        log.info("Initializing cron jobs...");
        List<CronJobEntity> jobs = cronJobRepository.findByEnabledTrue(PageRequest.of(0, 50)).getContent();
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
        return create(name, schedule, prompt, deliverTo, null);
    }

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo, String skills) {
        validateCronExpression(schedule);
        CronJobEntity entity = new CronJobEntity();
        entity.setName(name);
        entity.setSchedule(schedule);
        entity.setPrompt(prompt);
        entity.setDeliverTo(deliverTo);
        entity.setSkills(skills);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());
        entity = cronJobRepository.save(entity);
        if (properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Created cron job: {} (schedule: {}, skills: {})", name, schedule, skills);
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
            // Per-job lock: skip if another execution of the same job is still running
            ReentrantLock lock = jobLocks.computeIfAbsent(jobId, k -> new ReentrantLock());
            if (!lock.tryLock()) {
                log.warn("Cron job {} already running, skipping this execution", jobId);
                // Still reschedule
                CronJobEntity job = cronJobRepository.findById(jobId).orElse(null);
                if (job != null && job.isEnabled()) {
                    scheduleJob(job);
                }
                return;
            }
            try {
                CronJobEntity job = cronJobRepository.findById(jobId).orElse(null);
                if (job == null || !job.isEnabled()) return;
                executeJob(job);
                // Reschedule
                scheduleJob(job);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            log.error("Error executing cron job {}: {}", jobId, e.getMessage());
        }
    }

    private void executeJob(CronJobEntity job) {
        log.info("Executing cron job: {} (deliverTo: {}, skills: {})", job.getName(), job.getDeliverTo(), job.getSkills());
        try {
            if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                // Deliver to platform — could be extended for telegram, discord, etc.
                log.info("Delivering cron job '{}' output to: {}", job.getName(), job.getDeliverTo());
            }
            // S17: Load attached skills and inject into agent context
            String enhancedPrompt = job.getPrompt();
            String loadedSkills = loadJobSkills(job.getSkills());
            if (loadedSkills != null && !loadedSkills.isBlank()) {
                enhancedPrompt = loadedSkills + "\n\n---\n\n" + job.getPrompt();
                log.debug("Injected {} skills into cron job '{}'", job.getSkills(), job.getName());
            }
            // Run the prompt through the agent runtime with retry on failure
            AgentRuntimeService runtimeService = agentRuntimeServiceProvider.getIfAvailable();
            if (runtimeService != null) {
                int maxRetries = 2;
                int attempt = 0;
                boolean success = false;
                while (attempt <= maxRetries && !success) {
                    try {
                        runtimeService.runBackground(enhancedPrompt, null);
                        success = true;
                    } catch (Exception llmEx) {
                        attempt++;
                        if (attempt > maxRetries) {
                            log.error("Cron job '{}' LLM call failed after {} attempts: {}",
                                job.getName(), maxRetries + 1, llmEx.getMessage());
                            throw llmEx;
                        }
                        log.warn("Cron job '{}' LLM call attempt {}/{} failed, retrying in 5s: {}",
                            job.getName(), attempt, maxRetries + 1, llmEx.getMessage());
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Cron job retry interrupted", ie);
                        }
                    }
                }
            } else {
                log.warn("AgentRuntimeService not available, skipping cron job execution: {}", job.getName());
            }
            job.setLastRunAt(Instant.now());
            cronJobRepository.save(job);
        } catch (Exception e) {
            log.error("Failed to execute cron job {}: {} — job will be rescheduled", job.getName(), e.getMessage());
        }
    }

    /**
     * S17: Load skills attached to a cron job and return their content for injection.
     *
     * @param skillsCsv comma-separated skill names
     * @return combined skill content, or null if no skills attached
     */
    private String loadJobSkills(String skillsCsv) {
        if (skillsCsv == null || skillsCsv.isBlank()) {
            return null;
        }
        if (skillManager == null) {
            log.debug("SkillManager not available — cannot load cron job skills");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String skillName : skillsCsv.split(",")) {
            String trimmed = skillName.trim();
            if (trimmed.isEmpty()) continue;
            try {
                String content = skillManager.getSkill(trimmed);
                if (content != null && !content.isBlank()) {
                    sb.append("=== Skill: ").append(trimmed).append(" ===\n");
                    sb.append(content).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to load skill '{}' for cron job: {}", trimmed, e.getMessage());
            }
        }
        return sb.toString().trim();
    }

    private void cancelJob(UUID id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    private long calculateDelaySeconds(String cronExpression) {
        // Try human-readable interval first
        try {
            return Math.max(1, parseIntervalSeconds(cronExpression));
        } catch (Exception e) {
            log.debug("Schedule '{}' is not a human-readable interval, trying cron: {}", cronExpression, e.getMessage());
            // Not a human-readable interval, try cron expression
        }
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
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule cannot be null or blank");
        }
        // Try human-readable interval first (e.g. "every 5m", "every 2h", "30m", "1h")
        if (tryParseInterval(schedule)) return;
        // Fall back to standard cron expression
        try {
            cronParser.parse(schedule);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + schedule + " — " + e.getMessage());
        }
    }

    /**
     * Try to parse a human-readable interval like "every 5m", "every 2h", "30m", "1h".
     * @return true if parsed successfully, false otherwise
     */
    private boolean tryParseInterval(String schedule) {
        try {
            parseIntervalSeconds(schedule);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse a human-readable interval string into seconds.
     * Supports: "every 5m", "every 2h", "every 30s", "5m", "2h", "30s", "1d".
     * @return interval in seconds
     */
    private long parseIntervalSeconds(String schedule) {
        String normalized = schedule.trim().toLowerCase().replaceAll("^every\\s+", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+)\\s*([smhd])$").matcher(normalized);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognized interval format: " + schedule);
        }
        long value = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> throw new IllegalArgumentException("Unknown time unit: " + m.group(2));
        };
    }
}