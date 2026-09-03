package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
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
import java.time.Instant;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class CronJobService {

    /** Hermes parity: every agent-driven cron run is auto-delivered and may suppress an empty report. */
    private static final String CRON_EXECUTION_HINT = """
        [IMPORTANT: You are running as a scheduled cron job. DELIVERY: Your final response will be automatically delivered to the user — do NOT use send_message or try to deliver the output yourself. Just produce your report/output as your final response and the system handles the rest. SILENT: If there is genuinely nothing new to report, respond with exactly \"[SILENT]\" (nothing else) to suppress delivery. Never combine [SILENT] with content — either report your findings normally, or say [SILENT] and nothing more.]

        """;

    private final CronJobRepository cronJobRepository;
    private final ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    private final AgentProperties properties;
    private final SkillManager skillManager;
    // h72: Cron execution ledger repository.
    private final CronExecutionLogRepository cronExecutionLogRepository;
    private final MessageRepository messageRepository;
    // Audit C4: programmatic transactions for scheduler-thread multi-write sequences
    // (@Transactional would be bypassed here because executeAndReschedule is a
    // self-invocation from the scheduler lambda).
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    // M-Cron: pure schedule parsing extracted to CronScheduleParser
    private final CronScheduleParser scheduleParser;

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

    // Schedule kind constants — delegate to CronScheduleParser
    private static final String KIND_ONCE = CronScheduleParser.KIND_ONCE;

    // h74: Maximum consecutive failures before backing off significantly.
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    // h74: Base backoff seconds for backend unavailability.
    private static final long BACKEND_UNAVAILABLE_BACKOFF_SECONDS = 300; // 5 minutes

    // HERMES-SYNC Bug 1: Cron nudge — "automation needs attention" message.
    private static final String AUTOMATION_NEEDS_ATTENTION_MSG =
        "⚠️ Automation needs attention: cron job '{}' has failed {} consecutive times. " +
        "Last error: {}";

    // ── Schedule parsing delegated to CronScheduleParser ──

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

    public CronJobEntity create(
        String userId,
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl
    ) {
        CronJobEntity entity = create(name, schedule, prompt, deliverTo, skills, contextFrom,
            repeatCount, script, noAgent, enabledToolsets, workdir, modelProvider, modelName, baseUrl);
        entity.setUserId(userId);
        return cronJobRepository.save(entity);
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
        scheduleParser.validate(schedule);

        // no_agent jobs require a script
        if (noAgent && (script == null || script.isBlank())) {
            throw new IllegalArgumentException("no_agent=true requires a script — the script is the job.");
        }

        // Parse schedule to detect one-shot
        CronScheduleParser.ScheduleInfo scheduleInfo = scheduleParser.parse(schedule);

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
        if (KIND_ONCE.equals(scheduleInfo.kind()) && effectiveRepeat == null) {
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

    /**
     * List cron jobs scoped to a user. Null userId = admin/global access (all jobs).
     * Non-null userId = only that user's jobs (userId field matches).
     */
    public List<CronJobEntity> list(String userId) {
        if (userId == null) return list();
        return cronJobRepository.findByUserId(userId);
    }

    // ── Update overloads (backward-compatible) ──

    public CronJobEntity update(UUID id, String name, String schedule, String prompt, String deliverTo, Boolean enabled) {
        return update(id, name, schedule, prompt, deliverTo, enabled,
            null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Ownership guard: a non-admin authenticated user may only modify/run
     * their own cron jobs. Null/absent user id on the entity (legacy rows)
     * is treated as shared — allowed for backward compatibility.
     */
    private void requireOwnership(CronJobEntity entity) {
        String scoped = UserContext.scopeUserId();
        if (scoped != null && entity.getUserId() != null && !scoped.equals(entity.getUserId())) {
            throw new SecurityException("Cron job does not belong to the current user");
        }
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
        requireOwnership(entity);

        if (name != null) entity.setName(name);
        if (schedule != null) {
            scheduleParser.validate(schedule);
            entity.setSchedule(schedule);
            // Re-detect one-shot for repeatCount auto-set
            CronScheduleParser.ScheduleInfo scheduleInfo = scheduleParser.parse(schedule);
            if (KIND_ONCE.equals(scheduleInfo.kind()) && entity.getRepeatCount() == null) {
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

    /**
     * h76: Mark a job's latest run as delivered (high-water mark for the bot-side
     * delivery poller). Called after the run's output was successfully pushed to
     * the user's chat so each run is delivered exactly once.
     */
    @org.springframework.transaction.annotation.Transactional
    public CronJobEntity markDelivered(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        entity.setLastDeliveredRunAt(java.time.Instant.now());
        return cronJobRepository.save(entity);
    }

    public CronJobEntity pause(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        requireOwnership(entity);
        entity.setEnabled(false);
        entity = cronJobRepository.save(entity);
        cancelJob(id);
        log.info("Paused cron job: {}", id);
        return entity;
    }

    public CronJobEntity resume(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        requireOwnership(entity);
        entity.setEnabled(true);
        entity = cronJobRepository.save(entity);
        if (properties.getCron().isEnabled()) {
            scheduleJob(entity);
        }
        log.info("Resumed cron job: {}", id);
        return entity;
    }

    public boolean exists(UUID id) {
        return cronJobRepository.existsById(id);
    }

    public void remove(UUID id) {
        cronJobRepository.findById(id).ifPresent(this::requireOwnership);
        cancelJob(id);
        cronJobRepository.deleteById(id);
        log.info("Removed cron job: {}", id);
    }

    public CronJobEntity runNow(UUID id) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        requireOwnership(entity);
        executeJob(entity);
        return entity;
    }

    public Optional<CronJobEntity> findByName(String name) {
        return cronJobRepository.findByName(name);
    }

    public Optional<CronJobEntity> findById(UUID id) {
        return cronJobRepository.findById(id);
    }

    // ── Scheduling ──

    private void scheduleJob(CronJobEntity job) {
        try {
            long delaySeconds = scheduleParser.calculateDelaySeconds(job.getSchedule());
            ScheduledFuture<?> future = scheduler.schedule(
                () -> executeAndReschedule(job.getId()),
                delaySeconds, TimeUnit.SECONDS
            );
            ScheduledFuture<?> old = scheduledTasks.put(job.getId(), future);
            if (old != null) old.cancel(false);
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
                    long delaySeconds = Math.max(backoff, scheduleParser.calculateDelaySeconds(job.getSchedule()));
                    ScheduledFuture<?> future = scheduler.schedule(
                        () -> executeAndReschedule(jobId), delaySeconds, TimeUnit.SECONDS);
                    ScheduledFuture<?> old = scheduledTasks.put(jobId, future);
                    if (old != null) old.cancel(false);
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
            String contextFromOutput = loadContextFromOutput(job);
            if (contextFromOutput != null && !contextFromOutput.isBlank()) {
                enhancedPrompt = contextFromOutput + "\n\n" + enhancedPrompt;
                log.debug("Injected context_from output into cron job '{}'", job.getName());
            }

            // Hermes cron/scheduler.py: scheduled output is auto-delivered; the
            // model must use [SILENT] for a genuinely empty report.
            enhancedPrompt = CRON_EXECUTION_HINT + enhancedPrompt;

            // Cron runtime constraints must be enforced by the runtime, not merely
            // exposed to the model as advisory prompt text.
            Map<String, String> runtimeMetadata = new java.util.HashMap<>();
            if (job.getEnabledToolsets() != null && !job.getEnabledToolsets().isBlank()) {
                runtimeMetadata.put("delegation_toolsets", job.getEnabledToolsets());
                log.debug("Cron job '{}' restricts toolsets to {}", job.getName(), job.getEnabledToolsets());
            }
            if (job.getWorkdir() != null && !job.getWorkdir().isBlank()) {
                runtimeMetadata.put(TerminalTool.META_WORKDIR, job.getWorkdir());
                log.debug("Cron job '{}' uses workdir {}", job.getName(), job.getWorkdir());
            }
            // Model settings still require provider-transport support; do not claim
            // they are applied until a request-scoped client override exists.

            // Run the prompt through the agent runtime with retry on failure
            AgentRuntimeService runtimeService = agentRuntimeServiceProvider.getIfAvailable();
            String lastRunSessionId = null;
            if (runtimeService != null) {
                int maxRetries = 2;
                int attempt = 0;
                boolean success = false;
                while (attempt <= maxRetries && !success) {
                    try {
                        lastRunSessionId = runtimeMetadata.isEmpty()
                            ? runtimeService.runBackground(enhancedPrompt, null, true)
                            : runtimeService.runBackground(enhancedPrompt, null, true, runtimeMetadata);
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
            // h75: remember the session this run produced so delivery can read its output.
            if (lastRunSessionId != null) {
                try {
                    job.setLastRunSessionId(UUID.fromString(lastRunSessionId));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Cron job '{}' produced non-UUID session id: {}", job.getName(), lastRunSessionId);
                }
            }
            cronJobRepository.save(job);
            // h72: Record successful execution in the ledger. Persist the final
            // assistant output so downstream context_from jobs receive actual data.
            String outputText = loadLastRunOutput(job.getLastRunSessionId());
            recordExecution(job.getId(), startedAt, Instant.now(), "success", null, outputText);
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
            recordExecution(job.getId(), startedAt, Instant.now(), status, errorMsg, null);
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
     * @param outputText assistant output for successful context_from chaining
     */
    private void recordExecution(UUID jobId, Instant startedAt, Instant finishedAt, String status,
                                 String errorMessage, String outputText) {
        try {
            if (cronExecutionLogRepository != null) {
                CronExecutionLogEntity logEntry = CronExecutionLogEntity.create(jobId, startedAt, finishedAt, status, errorMessage);
                logEntry.setOutputText(outputText);
                cronExecutionLogRepository.save(logEntry);
            }
        } catch (Exception e) {
            log.warn("Failed to record cron execution log for job {}: {}", jobId, e.getMessage());
        }
    }

    private String loadLastRunOutput(UUID sessionId) {
        if (sessionId == null || messageRepository == null) {
            return null;
        }
        try {
            return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .map(message -> message.getContent() == null ? "" : message.getContent())
                .filter(content -> !content.isBlank())
                .reduce((ignored, latest) -> latest)
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to load cron output for session {}: {}", sessionId, e.getMessage());
            return null;
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

            // H10: Read stdout and stderr concurrently via gobbler threads to avoid
            // pipe-buffer deadlock when the child fills one pipe while we block on the other.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread stdoutGobbler = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) { }
            }, "cron-stdout-gobbler");
            Thread stderrGobbler = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) { }
            }, "cron-stderr-gobbler");
            stdoutGobbler.setDaemon(true);
            stderrGobbler.setDaemon(true);
            stdoutGobbler.start();
            stderrGobbler.start();

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutGobbler.interrupt();
                stderrGobbler.interrupt();
                log.error("Cron job '{}': script timed out after 120s", job.getName());
                return;
            }

            stdoutGobbler.join(5000);
            stderrGobbler.join(5000);

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
            // H11: Don't silently swallow no_agent execution failures — mark the job as error.
            log.error("Cron job '{}' (no_agent): script execution failed: {}", job.getName(), e.getMessage());
            job.setLastStatus("error");
            job.setLastError(e.getMessage());
            job.setLastErrorAt(Instant.now());
            cronJobRepository.save(job);
            throw new RuntimeException(e);
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
    private String loadContextFromOutput(CronJobEntity job) {
        String contextFromCsv = job.getContextFrom();
        if (contextFromCsv == null || contextFromCsv.isBlank() || cronExecutionLogRepository == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String jobIdStr : contextFromCsv.split(",")) {
            String reference = jobIdStr.trim();
            if (reference.isEmpty()) continue;
            UUID sourceJobId;
            boolean isSelf = "self".equalsIgnoreCase(reference);
            try {
                sourceJobId = isSelf ? job.getId() : UUID.fromString(reference);
            } catch (IllegalArgumentException e) {
                log.warn("context_from: invalid job ID '{}', skipping", reference);
                continue;
            }
            if (sourceJobId == null) {
                log.warn("context_from: current job has no id, skipping self reference");
                continue;
            }
            try {
                var sourceJob = cronJobRepository.findById(sourceJobId).orElse(null);
                var latest = cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(sourceJobId).orElse(null);
                if (sourceJob == null || latest == null || latest.getOutputText() == null
                    || latest.getOutputText().isBlank()) {
                    continue;
                }
                if (isSelf) {
                    sb.append("## Your previous run's output\n");
                    sb.append("The following is this job's most recent output from its previous run. ")
                        .append("Use it for continuity: avoid repeating what was already reported, and continue ")
                        .append("where the last run left off.\n\n```\n")
                        .append(latest.getOutputText().trim()).append("\n```\n\n");
                } else {
                    sb.append("## Output from job '").append(sourceJob.getName()).append("'\n");
                    sb.append("The following is the most recent output from a preceding cron job. ")
                        .append("Use it as context for your analysis.\n\n```\n")
                        .append(latest.getOutputText().trim()).append("\n```\n\n");
                }
            } catch (Exception e) {
                log.warn("context_from: failed to load output for job '{}': {}", reference, e.getMessage());
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
}