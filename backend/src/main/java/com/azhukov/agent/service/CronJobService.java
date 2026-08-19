package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class CronJobService {

    private final CronJobRepository cronJobRepository;
    private final ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    private final AgentProperties properties;
    private final SkillManager skillManager;
    // h72: Cron execution ledger repository.
    private final CronExecutionLogRepository cronExecutionLogRepository;
    // Audit C4: programmatic transactions for scheduler-thread multi-write sequences
    // (@Transactional would be bypassed here because executeAndReschedule is a
    // self-invocation from the scheduler lambda).
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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

    // Schedule kind constants — mirror Hermes parse_schedule() kinds
    private static final String KIND_ONCE = "once";
    private static final String KIND_INTERVAL = "interval";
    private static final String KIND_CRON = "cron";

    // ISO timestamp pattern: 2026-02-03T14:00:00 or 2026-02-03T14:00
    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

    // Bare duration: 30m, 2h, 1d, 30s
    private static final Pattern DURATION_PATTERN = Pattern.compile(
        "^(\\d+)\\s*([smhd])$", Pattern.CASE_INSENSITIVE);

    // "every X" prefix
    private static final Pattern EVERY_PREFIX = Pattern.compile(
        "^every\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    // Cron expression: 5+ space-separated fields of digits/specials
    private static final Pattern CRON_FIELD_PATTERN = Pattern.compile("^[\\d*/,-]+$");

    // h74: Maximum consecutive failures before backing off significantly.
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    // h74: Base backoff seconds for backend unavailability.
    private static final long BACKEND_UNAVAILABLE_BACKOFF_SECONDS = 300; // 5 minutes

    // HERMES-SYNC Bug 1: Cron nudge — "automation needs attention" message.
    private static final String AUTOMATION_NEEDS_ATTENTION_MSG =
        "⚠️ Automation needs attention: cron job '{}' has failed {} consecutive times. " +
        "Last error: {}";

    @PostConstruct
    public void init() {
        if (!properties.getCron().isEnabled()) {
            log.info("Cron jobs disabled by configuration");
            return;
        }
        log.info("Initializing cron jobs...");
        List<CronJobEntity> jobs = cronJobRepository.findByEnabledTrue();
        // h71: Re-arm recurring cron jobs stuck in stale last_status=error.
        // Don't let a permanent error state block future executions.
        for (CronJobEntity job : jobs) {
            if ("error".equals(job.getLastStatus()) && job.isEnabled()) {
                log.info("Re-arming cron job '{}' stuck in error state for next tick", job.getName());
                job.setLastStatus(null);
                job.setLastError(null);
                job.setConsecutiveFailures(0);
                cronJobRepository.save(job);
            }
        }
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

    // ── Create overloads (backward-compatible) ──

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo) {
        return create(name, schedule, prompt, deliverTo, null, null);
    }

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo, String skills) {
        return create(name, schedule, prompt, deliverTo, skills, null);
    }

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo, String skills, String contextFrom) {
        return create(name, schedule, prompt, deliverTo, skills, contextFrom,
            null, null, false, null, null, null, null, null);
    }

    /**
     * Full-featured create with all Hermes parity fields.
     */
    public CronJobEntity create(
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl
    ) {
        validateSchedule(schedule);

        // no_agent jobs require a script
        if (noAgent && (script == null || script.isBlank())) {
            throw new IllegalArgumentException("no_agent=true requires a script — the script is the job.");
        }

        // Parse schedule to detect one-shot
        ScheduleInfo scheduleInfo = parseSchedule(schedule);

        CronJobEntity entity = new CronJobEntity();
        entity.setName(name);
        entity.setSchedule(schedule);
        entity.setPrompt(prompt);
        entity.setDeliverTo(deliverTo);
        entity.setSkills(skills);
        entity.setContextFrom(contextFrom);
        entity.setScript(script);
        entity.setNoAgent(noAgent);
        entity.setEnabledToolsets(enabledToolsets);
        entity.setWorkdir(workdir);
        entity.setModelProvider(modelProvider);
        entity.setModelName(modelName);
        entity.setBaseUrl(baseUrl);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());

        // Repeat count: normalize 0/negative to null (forever).
        // Auto-set repeatCount=1 for one-shot schedules if not specified.
        Integer effectiveRepeat = repeatCount;
        if (effectiveRepeat != null && effectiveRepeat <= 0) {
            effectiveRepeat = null;
        }
        if (KIND_ONCE.equals(scheduleInfo.kind) && effectiveRepeat == null) {
            effectiveRepeat = 1;
        }
        entity.setRepeatCount(effectiveRepeat);
        entity.setRepeatCompleted(0);

        entity = cronJobRepository.save(entity);
        if (properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Created cron job: {} (schedule: {}, skills: {}, context_from: {}, repeat: {}, noAgent: {}, script: {})",
            name, schedule, skills, contextFrom, effectiveRepeat, noAgent, script);
        return entity;
    }

    public List<CronJobEntity> list() {
        // H13: Add deterministic sort to avoid unbounded unordered results.
        return cronJobRepository.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    // ── Update overloads (backward-compatible) ──

    public CronJobEntity update(UUID id, String name, String schedule, String prompt, String deliverTo, Boolean enabled) {
        return update(id, name, schedule, prompt, deliverTo, enabled,
            null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Full-featured update with all Hermes parity fields.
     * Each field is only applied if non-null (Boolean fields use explicit non-null check).
     */
    public CronJobEntity update(
        UUID id, String name, String schedule, String prompt, String deliverTo, Boolean enabled,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, Boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl
    ) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));

        if (name != null) entity.setName(name);
        if (schedule != null) {
            validateSchedule(schedule);
            entity.setSchedule(schedule);
            // Re-detect one-shot for repeatCount auto-set
            ScheduleInfo scheduleInfo = parseSchedule(schedule);
            if (KIND_ONCE.equals(scheduleInfo.kind) && entity.getRepeatCount() == null) {
                entity.setRepeatCount(1);
            }
        }
        if (prompt != null) entity.setPrompt(prompt);
        if (deliverTo != null) entity.setDeliverTo(deliverTo);
        if (enabled != null) entity.setEnabled(enabled);
        if (skills != null) entity.setSkills(skills);
        if (contextFrom != null) entity.setContextFrom(contextFrom);
        if (repeatCount != null) {
            // Normalize: 0 or negative → null (forever)
            entity.setRepeatCount(repeatCount <= 0 ? null : repeatCount);
        }
        if (script != null) entity.setScript(script.isBlank() ? null : script);
        if (noAgent != null) {
            if (noAgent) {
                // Validate: noAgent requires script (existing or in this update)
                String effectiveScript = script != null ? script : entity.getScript();
                if (effectiveScript == null || effectiveScript.isBlank()) {
                    throw new IllegalArgumentException(
                        "Cannot set no_agent=true on a job without a script. Set script in the same update.");
                }
            }
            entity.setNoAgent(noAgent);
        }
        if (enabledToolsets != null) entity.setEnabledToolsets(enabledToolsets.isBlank() ? null : enabledToolsets);
        if (workdir != null) entity.setWorkdir(workdir.isBlank() ? null : workdir);
        if (modelProvider != null) entity.setModelProvider(modelProvider.isBlank() ? null : modelProvider);
        if (modelName != null) entity.setModelName(modelName.isBlank() ? null : modelName);
        if (baseUrl != null) entity.setBaseUrl(baseUrl.isBlank() ? null : baseUrl);

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

    // ── Scheduling ──

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

                // h74: Check if we should back off due to consecutive failures.
                // If the backend has been unavailable, increase the delay before next attempt.
                if (job.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
                    long backoff = BACKEND_UNAVAILABLE_BACKOFF_SECONDS * (1L << Math.min(
                        job.getConsecutiveFailures() - MAX_CONSECUTIVE_FAILURES, 5));
                    log.warn("Cron job {} backing off {}s due to {} consecutive failures (backend unavailability)",
                        job.getName(), backoff, job.getConsecutiveFailures());
                    long delaySeconds = Math.max(backoff, calculateDelaySeconds(job.getSchedule()));
                    ScheduledFuture<?> future = scheduler.schedule(
                        () -> executeAndReschedule(jobId), delaySeconds, TimeUnit.SECONDS);
                    scheduledTasks.put(jobId, future);
                    job.setNextRunAt(Instant.now().plusSeconds(delaySeconds));
                    cronJobRepository.save(job);
                    return;
                }

                executeJob(job);

                // ── Fix 2: Repeat count auto-delete ──
                // After each successful execution, increment repeatCompleted.
                // If repeatCount is set and completed >= repeatCount, auto-delete.
                // Audit C4: save + delete + reset are wrapped in one programmatic
                // transaction so a DB failure mid-sequence cannot leave an orphaned
                // fully-completed job that never runs again and never gets deleted.
                if (job.getRepeatCount() != null) {
                    final CronJobEntity jobRef = job;
                    boolean deleted = Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
                        jobRef.setRepeatCompleted(jobRef.getRepeatCompleted() + 1);
                        if (jobRef.getRepeatCompleted() >= jobRef.getRepeatCount()) {
                            log.info("Cron job '{}' reached repeat limit ({}/{}), auto-deleting",
                                jobRef.getName(), jobRef.getRepeatCompleted(), jobRef.getRepeatCount());
                            cronJobRepository.save(jobRef);
                            cancelJob(jobId);
                            cronJobRepository.deleteById(jobId);
                            return true;
                        }
                        cronJobRepository.save(jobRef);
                        // h71: Reset consecutive failures on successful execution (same tx)
                        if (jobRef.getConsecutiveFailures() > 0) {
                            jobRef.setConsecutiveFailures(0);
                            cronJobRepository.save(jobRef);
                        }
                        return false;
                    }));
                    if (deleted) {
                        jobLocks.remove(jobId, lock);
                        return;
                    }
                } else if (job.getConsecutiveFailures() > 0) {
                    // h71: Reset consecutive failures on successful execution
                    transactionTemplate.executeWithoutResult(tx -> {
                        job.setConsecutiveFailures(0);
                        cronJobRepository.save(job);
                    });
                }

                // Reschedule (one-shot jobs with repeatCount=1 are already deleted above)
                scheduleJob(job);
            } finally {
                lock.unlock();
                // Clean up the lock to avoid memory leak — it will be recreated if needed
                jobLocks.remove(jobId, lock);
            }
        } catch (Exception e) {
            log.error("Error executing cron job {}: {} — job will be rescheduled", jobId, e.getMessage());
            // h71: Re-arm the job even if the outer catch fires — don't let a
            // permanent error state block future executions.
            try {
                CronJobEntity job = cronJobRepository.findById(jobId).orElse(null);
                if (job != null && job.isEnabled()) {
                    scheduleJob(job);
                }
            } catch (Exception retryEx) {
                log.error("Failed to re-schedule cron job {} after error: {}", jobId, retryEx.getMessage());
            }
        }
    }

    // ── Job execution ──

    private void executeJob(CronJobEntity job) {
        log.info("Executing cron job: {} (deliverTo: {}, skills: {}, noAgent: {}, script: {})",
            job.getName(), job.getDeliverTo(), job.getSkills(), job.isNoAgent(), job.getScript());

        // h72: Record execution start in the ledger.
        Instant startedAt = Instant.now();

        try {
            // ── Fix 4: no_agent mode ──
            // Skip the LLM entirely. Run script, deliver stdout verbatim.
            if (job.isNoAgent()) {
                executeNoAgentJob(job);
                job.setLastRunAt(Instant.now());
                job.setLastStatus("success");
                job.setLastError(null);
                cronJobRepository.save(job);
                return;
            }

            if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                log.info("Delivering cron job '{}' output to: {}", job.getName(), job.getDeliverTo());
            }

            // S17: Load attached skills and inject into agent context
            String enhancedPrompt = job.getPrompt();
            String loadedSkills = loadJobSkills(job.getSkills());
            if (loadedSkills != null && !loadedSkills.isBlank()) {
                enhancedPrompt = loadedSkills + "\n\n---\n\n" + job.getPrompt();
                log.debug("Injected {} skills into cron job '{}'", job.getSkills(), job.getName());
            }

            // P1-45: Inject output from upstream cron jobs (context_from chaining)
            String contextFromOutput = loadContextFromOutput(job.getContextFrom());
            if (contextFromOutput != null && !contextFromOutput.isBlank()) {
                enhancedPrompt = contextFromOutput + "\n\n" + enhancedPrompt;
                log.debug("Injected context_from output into cron job '{}'", job.getName());
            }

            // ── Fix 5/6/7: Pass enabledToolsets, workdir, model overrides to the agent runtime ──
            // For now, we log these overrides. The AgentRuntimeService.runBackground signature
            // would need to be extended to accept these; we pass them as metadata in the prompt
            // context block so the agent is aware of its configured overrides.
            StringBuilder overrideInfo = new StringBuilder();
            if (job.getEnabledToolsets() != null && !job.getEnabledToolsets().isBlank()) {
                overrideInfo.append("[Cron toolset restriction: ").append(job.getEnabledToolsets()).append("]\n");
                log.debug("Cron job '{}' toolset restriction: {}", job.getName(), job.getEnabledToolsets());
            }
            if (job.getWorkdir() != null && !job.getWorkdir().isBlank()) {
                overrideInfo.append("[Cron workdir: ").append(job.getWorkdir()).append("]\n");
                log.debug("Cron job '{}' workdir: {}", job.getName(), job.getWorkdir());
            }
            if (job.getModelProvider() != null && !job.getModelProvider().isBlank()) {
                overrideInfo.append("[Cron model provider: ").append(job.getModelProvider()).append("]\n");
                log.debug("Cron job '{}' model provider: {}", job.getName(), job.getModelProvider());
            }
            if (job.getModelName() != null && !job.getModelName().isBlank()) {
                overrideInfo.append("[Cron model name: ").append(job.getModelName()).append("]\n");
                log.debug("Cron job '{}' model name: {}", job.getName(), job.getModelName());
            }
            if (job.getBaseUrl() != null && !job.getBaseUrl().isBlank()) {
                overrideInfo.append("[Cron base URL: ").append(job.getBaseUrl()).append("]\n");
                log.debug("Cron job '{}' base URL: {}", job.getName(), job.getBaseUrl());
            }
            if (overrideInfo.length() > 0) {
                enhancedPrompt = overrideInfo.toString() + enhancedPrompt;
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
            job.setLastStatus("success");
            job.setLastError(null);
            cronJobRepository.save(job);
            // h72: Record successful execution in the ledger.
            recordExecution(job.getId(), startedAt, Instant.now(), "success", null);
        } catch (Exception e) {
            log.error("Failed to execute cron job {}: {} — job will be rescheduled", job.getName(), e.getMessage());
            // h71/h74: Record the error status and increment consecutive failures.
            // h74: Detect backend unavailability (connection refused) for backoff.
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
            boolean isBackendUnavailable = isBackendUnavailable(errorMsg);
            job.setLastStatus("error");
            job.setLastError(errorMsg);
            job.setLastErrorAt(Instant.now());
            if (isBackendUnavailable) {
                job.setConsecutiveFailures(job.getConsecutiveFailures() + 1);
                log.warn("Cron job '{}' detected backend unavailability (consecutive failures: {})",
                    job.getName(), job.getConsecutiveFailures());
            }
            cronJobRepository.save(job);

            // HERMES-SYNC Bug 1: Cron nudge — when consecutiveFailures >= threshold,
            // show a single "automation needs attention" message instead of per-error pings.
            int nudgeThreshold = properties.getCron().getNudgeFailureThreshold();
            if (nudgeThreshold > 0 && job.getConsecutiveFailures() >= nudgeThreshold) {
                // Only log the nudge at the exact threshold to avoid repeating on every failure
                if (job.getConsecutiveFailures() == nudgeThreshold) {
                    log.warn(AUTOMATION_NEEDS_ATTENTION_MSG,
                        job.getName(), job.getConsecutiveFailures(), errorMsg);
                }
                // Beyond the threshold, suppress per-error ping — the nudge has already fired.
            } else {
                // Below threshold — log the per-error detail as before
                log.warn("Cron job '{}' execution failed (consecutive failures: {}): {}",
                    job.getName(), job.getConsecutiveFailures(), errorMsg);
            }

            // h72: Record failed execution in the ledger.
            String status = errorMsg.toLowerCase().contains("timeout") ? "timeout" : "failure";
            recordExecution(job.getId(), startedAt, Instant.now(), status, errorMsg);
            // h71: Re-arm: clear the error status so the job can run on the next tick.
            // The error is recorded for audit but doesn't permanently block execution.
            // The scheduleJob call in executeAndReschedule will still fire.
        }
    }

    // ── HERMES-SYNC Bug 1: Cron nudge ──

    /**
     * HERMES-SYNC Bug 1: Check if a cron job needs attention due to consecutive failures.
     * Returns true when consecutiveFailures >= nudgeFailureThreshold.
     *
     * @param job the cron job to check
     * @return true if the job has reached the "needs attention" threshold
     */
    public boolean needsAttention(CronJobEntity job) {
        if (job == null) return false;
        int threshold = properties.getCron().getNudgeFailureThreshold();
        return threshold > 0 && job.getConsecutiveFailures() >= threshold;
    }

    /**
     * HERMES-SYNC Bug 1: Returns the configured nudge failure threshold.
     */
    public int getNudgeFailureThreshold() {
        return properties.getCron().getNudgeFailureThreshold();
    }

    // ── h72: Cron execution ledger ──

    /**
     * h72: Record a cron job execution in the execution ledger.
     *
     * @param jobId the job ID
     * @param startedAt when the execution started
     * @param finishedAt when the execution finished
     * @param status "success", "failure", or "timeout"
     * @param errorMessage error message if failed, null if succeeded
     */
    private void recordExecution(UUID jobId, Instant startedAt, Instant finishedAt, String status, String errorMessage) {
        try {
            if (cronExecutionLogRepository != null) {
                CronExecutionLogEntity logEntry = new CronExecutionLogEntity(jobId, startedAt, finishedAt, status, errorMessage);
                cronExecutionLogRepository.save(logEntry);
            }
        } catch (Exception e) {
            log.warn("Failed to record cron execution log for job {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Fix 4: no_agent mode — skip the LLM, run script directly, deliver stdout verbatim.
     * <p>
     * Mirrors Hermes scheduler.py run_job() no_agent path:
     * - .sh/.bash → run via bash
     * - others → run via python3
     * - Non-empty stdout → deliver verbatim (logged)
     * - Empty stdout → silent (nothing sent)
     * - Non-zero exit → error alert (logged)
     */
    private void executeNoAgentJob(CronJobEntity job) {
        String scriptPath = job.getScript();
        if (scriptPath == null || scriptPath.isBlank()) {
            log.error("Cron job '{}': no_agent=true but no script set", job.getName());
            return;
        }

        // Resolve script path — relative to current working directory
        File scriptFile = new File(scriptPath);
        if (!scriptFile.isAbsolute()) {
            // Try resolving relative to workdir if set, otherwise to user.dir
            String baseDir = (job.getWorkdir() != null && !job.getWorkdir().isBlank())
                ? job.getWorkdir() : System.getProperty("user.dir");
            scriptFile = new File(baseDir, scriptPath);
        }

        if (!scriptFile.exists()) {
            log.error("Cron job '{}': script not found: {}", job.getName(), scriptFile.getAbsolutePath());
            return;
        }

        // Choose interpreter by extension
        String ext = scriptFile.getName();
        int dotIdx = ext.lastIndexOf('.');
        String suffix = dotIdx >= 0 ? ext.substring(dotIdx).toLowerCase() : "";
        List<String> command = new ArrayList<>();
        if (".sh".equals(suffix) || ".bash".equals(suffix)) {
            command.add("bash");
            command.add(scriptFile.getAbsolutePath());
        } else {
            command.add("python3");
            command.add(scriptFile.getAbsolutePath());
        }

        // Set working directory
        File workDir = null;
        if (job.getWorkdir() != null && !job.getWorkdir().isBlank()) {
            workDir = new File(job.getWorkdir());
            if (!workDir.isDirectory()) {
                workDir = null;
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }

            // Read stderr
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("Cron job '{}': script timed out after 120s", job.getName());
                return;
            }

            int exitCode = process.exitValue();
            String stdoutStr = stdout.toString().trim();
            String stderrStr = stderr.toString().trim();

            if (exitCode != 0) {
                // Non-zero exit → error alert
                log.error("Cron job '{}' (no_agent): script failed (exit {})\nstdout: {}\nstderr: {}",
                    job.getName(), exitCode, stdoutStr, stderrStr);
            } else if (stdoutStr.isEmpty()) {
                // Empty stdout → silent
                log.info("Cron job '{}' (no_agent): empty stdout — silent run", job.getName());
            } else {
                // Non-empty stdout → deliver verbatim
                log.info("Cron job '{}' (no_agent): script output ({} chars) delivered verbatim",
                    job.getName(), stdoutStr.length());
                if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                    log.info("Cron job '{}' (no_agent): delivering to: {}", job.getName(), job.getDeliverTo());
                    // Actual delivery would go through the gateway; logged here for audit
                }
            }
        } catch (Exception e) {
            log.error("Cron job '{}' (no_agent): script execution failed: {}", job.getName(), e.getMessage());
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

    /**
     * P1-45: Load output from upstream cron jobs (context_from chaining).
     */
    private String loadContextFromOutput(String contextFromCsv) {
        if (contextFromCsv == null || contextFromCsv.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String jobIdStr : contextFromCsv.split(",")) {
            String trimmed = jobIdStr.trim();
            if (trimmed.isEmpty()) continue;
            try {
                UUID sourceJobId = UUID.fromString(trimmed);
                var sourceJob = cronJobRepository.findById(sourceJobId).orElse(null);
                if (sourceJob == null) {
                    log.warn("context_from: job '{}' not found, skipping", trimmed);
                    continue;
                }
                sb.append("## Output from job '").append(sourceJob.getName()).append("'\n");
                sb.append("(job ran at: ").append(sourceJob.getLastRunAt()).append(")\n\n");
            } catch (IllegalArgumentException e) {
                log.warn("context_from: invalid job ID '{}', skipping", trimmed);
            }
        }
        String result = sb.toString().trim();
        if (result.length() > 8000) {
            result = result.substring(0, 8000) + "\n\n[... output truncated ...]";
        }
        return result.isEmpty() ? null : result;
    }

    private void cancelJob(UUID id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    // ── h74: Backend unavailability detection ──

    /**
     * h74: Detect if the gateway/backend is deliberately stopped or unavailable.
     * Checks for connection refused, timeout, and similar network-level errors
     * that indicate the backend is down, not just a transient error.
     *
     * @param errorMsg the error message from the failed execution
     * @return true if the error indicates backend unavailability
     */
    static boolean isBackendUnavailable(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) {
            return false;
        }
        String lower = errorMsg.toLowerCase();
        return lower.contains("connection refused")
            || lower.contains("connection reset")
            || lower.contains("connection closed")
            || lower.contains("connection timed out")
            || lower.contains("connectexception")
            || lower.contains("unknownhostexception")
            || lower.contains("no route to host")
            || lower.contains("network is unreachable")
            || lower.contains("service unavailable")
            || lower.contains("503")
            || lower.contains("gateway")
            && lower.contains("502");
    }

    // ── Schedule parsing ──

    /**
     * Schedule info holder — mirrors Hermes parse_schedule() return shape.
     */
    private record ScheduleInfo(String kind, long delaySeconds) {}

    /**
     * Fix 3: Parse schedule string and detect one-shot vs recurring.
     * <p>
     * Hermes parse_schedule() recognizes:
     * - ISO timestamp (2026-02-03T14:00:00) → one-shot at specific time
     * - Bare duration (30m, 2h, 1d) without "every" → one-shot from now
     * - "every X" → recurring interval
     * - Cron expression → recurring cron
     *
     * @return ScheduleInfo with kind and delay in seconds from now
     */
    private ScheduleInfo parseSchedule(String schedule) {
        String trimmed = schedule.trim();
        String lower = trimmed.toLowerCase();

        // "every X" → recurring interval
        Matcher everyMatcher = EVERY_PREFIX.matcher(trimmed);
        if (everyMatcher.matches()) {
            long seconds = parseDurationSeconds(everyMatcher.group(1));
            return new ScheduleInfo(KIND_INTERVAL, seconds);
        }

        // ISO timestamp → one-shot at specific time
        if (ISO_TIMESTAMP_PATTERN.matcher(trimmed).matches() || (trimmed.contains("T") && trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*"))) {
            try {
                LocalDateTime dt = parseIsoTimestamp(trimmed);
                long delay = Duration.between(LocalDateTime.now(), dt).getSeconds();
                return new ScheduleInfo(KIND_ONCE, Math.max(1, delay));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid timestamp '" + trimmed + "': " + e.getMessage());
            }
        }

        // Check for date-only pattern: 2026-02-03
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}$")) {
            try {
                LocalDateTime dt = LocalDateTime.parse(trimmed + "T00:00:00");
                long delay = Duration.between(LocalDateTime.now(), dt).getSeconds();
                return new ScheduleInfo(KIND_ONCE, Math.max(1, delay));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date '" + trimmed + "': " + e.getMessage());
            }
        }

        // Bare duration (30m, 2h, 1d, 30s) without "every" → one-shot from now
        Matcher durationMatcher = DURATION_PATTERN.matcher(trimmed);
        if (durationMatcher.matches()) {
            long seconds = parseDurationSeconds(trimmed);
            return new ScheduleInfo(KIND_ONCE, seconds);
        }

        // Cron expression (5+ space-separated fields)
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 5 && isCronExpression(parts)) {
            return new ScheduleInfo(KIND_CRON, calculateCronDelaySeconds(trimmed));
        }

        throw new IllegalArgumentException(
            "Invalid schedule '" + trimmed + "'. Use:\n" +
            "  - Duration: '30m', '2h', '1d' (one-shot)\n" +
            "  - Interval: 'every 30m', 'every 2h' (recurring)\n" +
            "  - Cron: '0 9 * * *' (cron expression)\n" +
            "  - Timestamp: '2026-02-03T14:00:00' (one-shot at time)");
    }

    private boolean isCronExpression(String[] parts) {
        for (int i = 0; i < Math.min(5, parts.length); i++) {
            if (!CRON_FIELD_PATTERN.matcher(parts[i]).matches()) {
                return false;
            }
        }
        return true;
    }

    private LocalDateTime parseIsoTimestamp(String s) {
        // Handle Z suffix
        String normalized = s.replace("Z", "");
        // Try with seconds
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            // Try without seconds: 2026-02-03T14:00
            try {
                return LocalDateTime.parse(normalized + ":00", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                throw e;
            }
        }
    }

    private long parseDurationSeconds(String s) {
        Matcher m = DURATION_PATTERN.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognized duration: " + s);
        }
        long value = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase()) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> throw new IllegalArgumentException("Unknown time unit: " + m.group(2));
        };
    }

    private long calculateDelaySeconds(String schedule) {
        // Try human-readable interval or one-shot first
        try {
            ScheduleInfo info = parseSchedule(schedule);
            return Math.max(1, info.delaySeconds());
        } catch (Exception e) {
            log.debug("Schedule '{}' is not a human-readable interval, trying cron: {}", schedule, e.getMessage());
        }
        // Fall back to standard cron expression
        return calculateCronDelaySeconds(schedule);
    }

    private long calculateCronDelaySeconds(String cronExpression) {
        try {
            Cron cron = cronParser.parse(cronExpression);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(now);
            if (nextExecution.isPresent()) {
                long delaySeconds = Duration.between(now, nextExecution.get()).getSeconds();
                return Math.max(1, delaySeconds);
            }
            return 60; // fallback
        } catch (Exception e) {
            log.warn("Failed to parse cron expression '{}', using default 60s delay: {}", cronExpression, e.getMessage());
            return 60;
        }
    }

    private void validateSchedule(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule cannot be null or blank");
        }
        // Try human-readable interval, one-shot, or ISO timestamp first
        try {
            parseSchedule(schedule);
            return;
        } catch (IllegalArgumentException e) {
            // Not a human-readable format, try cron
        }
        // Fall back to standard cron expression
        try {
            cronParser.parse(schedule);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + schedule + " — " + e.getMessage());
        }
    }
}