package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.terminal.TerminalTool;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;
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
    private EventService eventService;
    private ProfileService profileService;

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
    private static final String DEFAULT_PROFILE = "default";
    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

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

    private static final String EMPTY_PAYLOAD_ERROR =
        "Cron job has nothing to run: the prompt is blank and no script or skill(s) are set. "
            + "Provide a prompt, a script, or at least one skill.";

    private static final String NO_AGENT_WITHOUT_SCRIPT_ERROR =
        "no_agent=true requires a script — the script is the job.";

    private static final int MAX_MONITOR_DIFF_CHARS = 4000;
    private static final int MAX_MONITOR_OUTPUT_CHARS = 8000;
    private static final int MAX_MONITOR_STORED_CHARS = 262_144;
    private static final int MAX_MONITOR_URL_BYTES = 262_144;
    private static final int MONITOR_URL_TIMEOUT_SECONDS = 30;

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

    @Autowired(required = false)
    void setEventService(EventService eventService) {
        this.eventService = eventService;
    }

    @Autowired(required = false)
    void setProfileService(ProfileService profileService) {
        this.profileService = profileService;
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

    public CronJobEntity createInProfile(String profile, String name, String schedule, String prompt, String deliverTo, String skills) {
        return createInProfile(profile, name, schedule, prompt, deliverTo, skills, null);
    }

    public CronJobEntity create(String name, String schedule, String prompt, String deliverTo, String skills, String contextFrom) {
        return create(name, schedule, prompt, deliverTo, skills, contextFrom,
            null, null, false, null, null, null, null, null);
    }

    public CronJobEntity createInProfile(String profile, String name, String schedule, String prompt, String deliverTo, String skills, String contextFrom) {
        return createInProfile(profile, name, schedule, prompt, deliverTo, skills, contextFrom,
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
        return createScoped(DEFAULT_PROFILE, name, schedule, prompt, deliverTo, skills, contextFrom, repeatCount,
            script, noAgent, enabledToolsets, workdir, modelProvider, modelName, baseUrl,
            null, false, null);
    }

    public CronJobEntity createInProfile(
        String profile,
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl
    ) {
        return createScoped(profile, name, schedule, prompt, deliverTo, skills, contextFrom, repeatCount,
            script, noAgent, enabledToolsets, workdir, modelProvider, modelName, baseUrl,
            null, false, null);
    }

    /**
     * Full-featured create with Hermes monitor, continuity, and session-attach fields.
     */
    public CronJobEntity create(
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl,
        String monitor, boolean continuityEnabled, UUID attachedSessionId
    ) {
        return createScoped(DEFAULT_PROFILE, name, schedule, prompt, deliverTo, skills, contextFrom,
            repeatCount, script, noAgent, enabledToolsets, workdir, modelProvider, modelName, baseUrl,
            monitor, continuityEnabled, attachedSessionId);
    }

    public CronJobEntity createInProfile(
        String profile,
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl,
        String monitor, boolean continuityEnabled, UUID attachedSessionId
    ) {
        return createScoped(profile, name, schedule, prompt, deliverTo, skills, contextFrom,
            repeatCount, script, noAgent, enabledToolsets, workdir, modelProvider, modelName, baseUrl,
            monitor, continuityEnabled, attachedSessionId);
    }

    private CronJobEntity createScoped(
        String profile,
        String name, String schedule, String prompt, String deliverTo,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl,
        String monitor, boolean continuityEnabled, UUID attachedSessionId
    ) {
        validateSchedule(schedule);
        String normalizedProfile = normalizeProfileForStorage(profile);
        String normalizedScript = normalizeCronScriptPath(script);
        String normalizedMonitor = normalizeMonitorSource(monitor);

        // no_agent jobs require a script
        if (noAgent && normalizedScript == null) {
            throw new IllegalArgumentException(NO_AGENT_WITHOUT_SCRIPT_ERROR);
        }
        if (noAgent && normalizedMonitor != null) {
            throw new IllegalArgumentException("monitor jobs cannot use no_agent=true; monitor gates an ordinary agent job.");
        }
        if (!noAgent && isBlank(prompt) && normalizedScript == null && isBlank(skills)) {
            throw new IllegalArgumentException(EMPTY_PAYLOAD_ERROR);
        }
        String effectiveContextFrom = continuityEnabled ? applyContinuity(contextFrom, true) : contextFrom;

        // Parse schedule to detect one-shot
        ScheduleInfo scheduleInfo = parseSchedule(schedule);

        CronJobEntity entity = new CronJobEntity();
        entity.setProfile(normalizedProfile);
        entity.setName(name);
        entity.setSchedule(schedule);
        entity.setPrompt(prompt);
        entity.setDeliverTo(deliverTo);
        entity.setSkills(skills);
        entity.setContextFrom(effectiveContextFrom);
        entity.setMonitor(normalizedMonitor);
        entity.setContinuityEnabled(continuityEnabled);
        entity.setAttachedSessionId(attachedSessionId);
        entity.setScript(normalizedScript);
        entity.setNoAgent(noAgent);
        entity.setEnabledToolsets(enabledToolsets);
        entity.setWorkdir(workdir);
        entity.setModelProvider(modelProvider);
        entity.setModelName(modelName);
        entity.setBaseUrl(baseUrl);
        ModelSnapshot snapshot = computeProviderModelSnapshots(
            normalizedProfile,
            modelProvider,
            modelName,
            baseUrl,
            noAgent);
        entity.setProviderSnapshot(snapshot.provider());
        entity.setModelSnapshot(snapshot.model());
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
        log.info("Created cron job: {} (profile: {}, schedule: {}, skills: {}, context_from: {}, repeat: {}, noAgent: {}, script: {})",
            name, normalizedProfile, schedule, skills, contextFrom, effectiveRepeat, noAgent, normalizedScript);
        return entity;
    }

    public List<CronJobEntity> list() {
        // H13: Add deterministic sort to avoid unbounded unordered results.
        return cronJobRepository.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    public List<CronJobEntity> list(boolean includeDisabled) {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
        return includeDisabled ? cronJobRepository.findAll(sort) : cronJobRepository.findByEnabledTrue(sort);
    }

    public List<CronJobEntity> listForProfile(String profile, boolean includeDisabled) {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
        String normalizedProfile = normalizeProfileForStorage(profile);
        return includeDisabled
            ? cronJobRepository.findByProfile(normalizedProfile, sort)
            : cronJobRepository.findByProfileAndEnabledTrue(normalizedProfile, sort);
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
        return update(id, name, schedule, prompt, deliverTo, enabled, skills, contextFrom,
            repeatCount, script, noAgent, enabledToolsets, workdir, modelProvider, modelName,
            baseUrl, null, null, null, null);
    }

    /**
     * Extended update with Hermes monitor, continuity, and session-attach fields.
     * Null means "leave unchanged"; blank monitor clears the monitor source.
     */
    public CronJobEntity update(
        UUID id, String name, String schedule, String prompt, String deliverTo, Boolean enabled,
        String skills, String contextFrom,
        Integer repeatCount,
        String script, Boolean noAgent,
        String enabledToolsets, String workdir,
        String modelProvider, String modelName, String baseUrl,
        String monitor, Boolean continuityEnabled, Boolean attachToSession, UUID currentSessionId
    ) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));

        boolean scriptUpdated = script != null;
        String normalizedScript = scriptUpdated ? normalizeCronScriptPath(script) : null;
        boolean monitorUpdated = monitor != null;
        String normalizedMonitor = monitorUpdated ? normalizeMonitorSource(monitor) : null;
        String previousModelProvider = entity.getModelProvider();
        String previousModelName = entity.getModelName();
        String previousBaseUrl = entity.getBaseUrl();
        boolean previousNoAgent = entity.isNoAgent();

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
        if (continuityEnabled != null) {
            entity.setContinuityEnabled(continuityEnabled);
            entity.setContextFrom(applyContinuity(entity.getContextFrom(), continuityEnabled));
        }
        if (repeatCount != null) {
            // Normalize: 0 or negative → null (forever)
            entity.setRepeatCount(repeatCount <= 0 ? null : repeatCount);
        }
        if (scriptUpdated) entity.setScript(normalizedScript);
        if (monitorUpdated) {
            String previous = entity.getMonitor();
            entity.setMonitor(normalizedMonitor);
            if (normalizedMonitor == null || !normalizedMonitor.equals(previous)) {
                entity.setMonitorLastHash(null);
                entity.setMonitorLastOutput(null);
                entity.setMonitorLastChangedAt(null);
            }
        }
        if (noAgent != null) {
            if (noAgent) {
                // Validate: noAgent requires script (existing or in this update)
                String effectiveScript = scriptUpdated ? normalizedScript : entity.getScript();
                if (effectiveScript == null || effectiveScript.isBlank()) {
                    throw new IllegalArgumentException(
                        "Cannot set no_agent=true on a job without a script. Set script in the same update.");
                }
            }
            entity.setNoAgent(noAgent);
        }
        if (attachToSession != null) {
            if (attachToSession) {
                if (currentSessionId == null) {
                    throw new IllegalArgumentException("attach_to_session=true requires a current session");
                }
                entity.setAttachedSessionId(currentSessionId);
            } else {
                entity.setAttachedSessionId(null);
            }
        }
        if (enabledToolsets != null) entity.setEnabledToolsets(enabledToolsets.isBlank() ? null : enabledToolsets);
        if (workdir != null) entity.setWorkdir(workdir.isBlank() ? null : workdir);
        if (modelProvider != null) entity.setModelProvider(modelProvider.isBlank() ? null : modelProvider);
        if (modelName != null) entity.setModelName(modelName.isBlank() ? null : modelName);
        if (baseUrl != null) entity.setBaseUrl(baseUrl.isBlank() ? null : baseUrl);

        if (inferenceAxesChanged(
            previousModelProvider,
            previousModelName,
            previousBaseUrl,
            previousNoAgent,
            entity.getModelProvider(),
            entity.getModelName(),
            entity.getBaseUrl(),
            entity.isNoAgent())) {
            ModelSnapshot snapshot = computeProviderModelSnapshots(
                jobProfile(entity),
                entity.getModelProvider(),
                entity.getModelName(),
                entity.getBaseUrl(),
                entity.isNoAgent());
            entity.setProviderSnapshot(snapshot.provider());
            entity.setModelSnapshot(snapshot.model());
        }

        if (entity.isNoAgent() && !isBlank(entity.getMonitor())) {
            throw new IllegalArgumentException("monitor jobs cannot use no_agent=true; clear monitor before enabling no_agent.");
        }

        if (prompt != null || skills != null || scriptUpdated || noAgent != null || monitorUpdated) {
            validateRunnablePayload(entity);
        }

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

    public boolean exists(UUID id) {
        return cronJobRepository.existsById(id);
    }

    public void remove(UUID id) {
        cancelJob(id);
        cronJobRepository.deleteById(id);
        jobLocks.remove(id);
        log.info("Removed cron job: {}", id);
    }

    public CronJobEntity runNow(UUID id) {
        return runNow(id, null);
    }

    public CronJobEntity runNow(UUID id, String extraPrompt) {
        return executeManualNow(id, extraPrompt);
    }

    public CronJobEntity runNowBackground(UUID id, String extraPrompt) {
        CronJobEntity entity = cronJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
        scheduler.execute(() -> {
            try {
                executeManualNow(id, extraPrompt);
            } catch (Exception e) {
                log.warn("Manual background cron run {} failed before execution: {}", id, e.getMessage());
            }
        });
        return entity;
    }

    private CronJobEntity executeManualNow(UUID id, String extraPrompt) {
        ReentrantLock lock = jobLocks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            CronJobEntity entity = cronJobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cron job not found: " + id));
            executeJob(entity, extraPrompt);
            return entity;
        } finally {
            lock.unlock();
        }
    }

    public Optional<CronJobEntity> findByName(String name) {
        return cronJobRepository.findByName(name);
    }

    public Optional<CronJobEntity> findByName(String name, String profile) {
        return cronJobRepository.findByNameAndProfile(name, normalizeProfileForStorage(profile));
    }

    public Optional<CronJobEntity> findById(UUID id) {
        return cronJobRepository.findById(id);
    }

    public Optional<CronJobEntity> findById(UUID id, String profile) {
        String normalizedProfile = normalizeProfileForStorage(profile);
        return cronJobRepository.findById(id)
            .filter(entity -> normalizedProfile.equals(jobProfile(entity)));
    }

    // ── Scheduling ──

    private void scheduleJob(CronJobEntity job) {
        try {
            long delaySeconds = calculateDelaySeconds(job.getSchedule());
            scheduleJob(job, delaySeconds);
        } catch (Exception e) {
            log.error("Failed to schedule cron job {}: {}", job.getName(), e.getMessage());
        }
    }

    private void scheduleJob(CronJobEntity job, long delaySeconds) {
        ScheduledFuture<?> future = scheduler.schedule(
            () -> executeAndReschedule(job.getId()),
            delaySeconds, TimeUnit.SECONDS
        );
        scheduledTasks.put(job.getId(), future);
        job.setNextRunAt(Instant.now().plusSeconds(delaySeconds));
        cronJobRepository.save(job);
        log.debug("Scheduled cron job '{}' to run in {} seconds", job.getName(), delaySeconds);
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

                CronExecutionOutcome outcome = executeJob(job);

                // ── Fix 2: Repeat count auto-delete ──
                // After each successful execution, increment repeatCompleted.
                // If repeatCount is set and completed >= repeatCount, auto-delete.
                // Audit C4: save + delete + reset are wrapped in one programmatic
                // transaction so a DB failure mid-sequence cannot leave an orphaned
                // fully-completed job that never runs again and never gets deleted.
                if (outcome.countsTowardRepeat() && job.getRepeatCount() != null) {
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
                } else if (outcome.resetFailureStreak() && job.getConsecutiveFailures() > 0) {
                    // h71: Reset consecutive failures on successful execution
                    transactionTemplate.executeWithoutResult(tx -> {
                        job.setConsecutiveFailures(0);
                        cronJobRepository.save(job);
                    });
                }

                // Reschedule (one-shot jobs with repeatCount=1 are already deleted above)
                scheduleJobAfterExecution(job, outcome);
            } finally {
                lock.unlock();
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

    private CronExecutionOutcome executeJob(CronJobEntity job) {
        return executeJob(job, null);
    }

    private CronExecutionOutcome executeJob(CronJobEntity job, String extraPrompt) {
        log.info("Executing cron job: {} (deliverTo: {}, skills: {}, noAgent: {}, script: {})",
            job.getName(), job.getDeliverTo(), job.getSkills(), job.isNoAgent(), job.getScript());

        // h72: Record execution start in the ledger.
        Instant startedAt = Instant.now();
        publishCronEvent("cron.started", job, startedAt, null);

        try {
            if (job.isNoAgent() && !isBlank(job.getMonitor())) {
                throw new IllegalStateException(
                    "monitor jobs cannot use no_agent=true; monitor gates an ordinary agent job.");
            }
            // ── Fix 4: no_agent mode ──
            // Skip the LLM entirely. Run script, deliver stdout verbatim.
            if (job.isNoAgent()) {
                ScriptRunResult scriptResult = executeNoAgentJob(job);
                if (!scriptResult.success()) {
                    throw new IllegalStateException(scriptResult.error());
                }
                job.setLastRunAt(Instant.now());
                job.setLastStatus("success");
                job.setLastError(null);
                cronJobRepository.save(job);
                recordExecution(job.getId(), startedAt, Instant.now(), "success", null);
                publishCronEvent("cron.success", job, startedAt, null);
                return CronExecutionOutcome.success();
            }

            MonitorOutcome monitorOutcome = evaluateMonitor(job);
            if (!monitorOutcome.success()) {
                throw new IllegalStateException(monitorOutcome.error());
            }
            if (monitorOutcome.configured() && !monitorOutcome.changed()) {
                job.setLastRunAt(Instant.now());
                job.setLastStatus("no_change");
                job.setLastError(null);
                cronJobRepository.save(job);
                recordExecution(job.getId(), startedAt, Instant.now(), "no_change", null);
                log.info("Cron job '{}' monitor output unchanged; skipping agent run", job.getName());
                publishCronEvent("cron.no_change", job, startedAt, null);
                return CronExecutionOutcome.noChange();
            }

            enforceCronModelDriftGuard(job);

            if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                log.info("Delivering cron job '{}' output to: {}", job.getName(), job.getDeliverTo());
            }

            // S17: Load attached skills and inject into agent context
            String enhancedPrompt = job.getPrompt() == null ? "" : job.getPrompt();
            if (extraPrompt != null && !extraPrompt.isBlank()) {
                enhancedPrompt = enhancedPrompt.isBlank()
                    ? extraPrompt
                    : enhancedPrompt + "\n\n---\n\n" + extraPrompt;
            }
            String loadedSkills = loadJobSkills(job.getSkills());
            if (loadedSkills != null && !loadedSkills.isBlank()) {
                enhancedPrompt = loadedSkills + "\n\n---\n\n" + enhancedPrompt;
                log.debug("Injected {} skills into cron job '{}'", job.getSkills(), job.getName());
            }

            // P1-45: Inject output from upstream cron jobs (context_from chaining)
            String contextFromOutput = loadContextFromOutput(job.getContextFrom());
            if (contextFromOutput != null && !contextFromOutput.isBlank()) {
                enhancedPrompt = contextFromOutput + "\n\n" + enhancedPrompt;
                log.debug("Injected context_from output into cron job '{}'", job.getName());
            }
            if (monitorOutcome.contextBlock() != null && !monitorOutcome.contextBlock().isBlank()) {
                enhancedPrompt = monitorOutcome.contextBlock() + "\n\n" + enhancedPrompt;
                log.debug("Injected monitor change context into cron job '{}'", job.getName());
            }

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
            String profile = jobProfile(job);
            if (!DEFAULT_PROFILE.equals(profile)) {
                runtimeMetadata.put("profile", profile);
            }
            // Model settings still require provider-transport support; do not claim
            // they are applied until a request-scoped client override exists.

            // Run the prompt through the agent runtime with retry on failure
            AgentRuntimeService runtimeService = agentRuntimeServiceProvider.getIfAvailable();
            String lastRunSessionId = null;
            if (runtimeService != null) {
                String targetSessionId = backgroundTargetSessionId(job);
                int maxRetries = 2;
                int attempt = 0;
                boolean success = false;
                while (attempt <= maxRetries && !success) {
                    try {
                        lastRunSessionId = runtimeMetadata.isEmpty()
                            ? runtimeService.runBackground(enhancedPrompt, targetSessionId, true)
                            : runtimeService.runBackground(enhancedPrompt, targetSessionId, true, runtimeMetadata);
                        success = true;
                    } catch (Exception llmEx) {
                        attempt++;
                        if (attempt > maxRetries) {
                            log.error("Cron job '{}' LLM call failed after {} attempts: {}",
                                job.getName(), maxRetries + 1, llmEx.getMessage());
                            throw llmEx;
                        }
                        log.warn("Cron job '{}' LLM call attempt {}/{} failed, retrying immediately: {}",
                            job.getName(), attempt, maxRetries + 1, llmEx.getMessage());
                    }
                }
            } else {
                log.warn("AgentRuntimeService not available, skipping cron job execution: {}", job.getName());
            }
            job.setLastRunAt(Instant.now());
            job.setLastStatus("success");
            job.setLastError(null);
            if (monitorOutcome.changed()) {
                job.setMonitorLastHash(monitorOutcome.hash());
                job.setMonitorLastOutput(capStoredMonitorOutput(monitorOutcome.output()));
                job.setMonitorLastChangedAt(monitorOutcome.changedAt());
            }
            // h75: remember the session this run produced so delivery can read its output.
            if (lastRunSessionId != null) {
                try {
                    job.setLastRunSessionId(UUID.fromString(lastRunSessionId));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Cron job '{}' produced non-UUID session id: {}", job.getName(), lastRunSessionId);
                }
            }
            cronJobRepository.save(job);
            // h72: Record successful execution in the ledger.
            recordExecution(job.getId(), startedAt, Instant.now(), "success", null);
            publishCronEvent("cron.success", job, startedAt, Map.of("monitor_changed", monitorOutcome.changed()));
            return CronExecutionOutcome.success();
        } catch (Exception e) {
            log.error("Failed to execute cron job {}: {} — job will be rescheduled", job.getName(), e.getMessage());
            // h71/h74: Record the error status and increment consecutive failures.
            // h74: Detect backend unavailability (connection refused) for backoff.
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
            boolean isBackendUnavailable = isBackendUnavailable(errorMsg);
            job.setLastStatus("error");
            job.setLastError(errorMsg);
            job.setLastErrorAt(Instant.now());
            job.setConsecutiveFailures(job.getConsecutiveFailures() + 1);
            if (isBackendUnavailable) {
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
            publishCronEvent("cron." + status, job, startedAt, Map.of("error", errorMsg));
            // h71: Re-arm: clear the error status so the job can run on the next tick.
            // The error is recorded for audit but doesn't permanently block execution.
            // The scheduleJob call in executeAndReschedule will still fire.
            return CronExecutionOutcome.failure(status);
        }
    }

    private void scheduleJobAfterExecution(CronJobEntity job, CronExecutionOutcome outcome) {
        if (!outcome.failed() || job.getConsecutiveFailures() < MAX_CONSECUTIVE_FAILURES) {
            scheduleJob(job);
            return;
        }
        long delaySeconds = Math.max(failureBackoffSeconds(job), calculateDelaySeconds(job.getSchedule()));
        log.warn("Cron job {} backing off {}s after {} consecutive failures",
            job.getName(), delaySeconds, job.getConsecutiveFailures());
        scheduleJob(job, delaySeconds);
    }

    private long failureBackoffSeconds(CronJobEntity job) {
        return BACKEND_UNAVAILABLE_BACKOFF_SECONDS * (1L << Math.min(
            Math.max(0, job.getConsecutiveFailures() - MAX_CONSECUTIVE_FAILURES), 5));
    }

    private void publishCronEvent(String eventType, CronJobEntity job, Instant startedAt, Map<String, Object> extraPayload) {
        if (eventService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job_id", job.getId());
        payload.put("name", job.getName());
        payload.put("profile", jobProfile(job));
        payload.put("status", job.getLastStatus());
        payload.put("deliver_to", job.getDeliverTo());
        payload.put("started_at", startedAt);
        payload.put("last_run_at", job.getLastRunAt());
        payload.put("last_run_session_id", job.getLastRunSessionId());
        payload.put("last_error", job.getLastError());
        payload.put("consecutive_failures", job.getConsecutiveFailures());
        if (extraPayload != null) {
            payload.putAll(extraPayload);
        }
        try {
            eventService.publish(eventType, jobProfile(job), job.getLastRunSessionId(), job.getId(), payload);
        } catch (RuntimeException e) {
            log.warn("Failed to publish cron event {} for {}: {}", eventType, job.getId(), e.getMessage());
        }
    }

    private record CronExecutionOutcome(String status, boolean failed, boolean countsTowardRepeat) {
        static CronExecutionOutcome success() {
            return new CronExecutionOutcome("success", false, true);
        }

        static CronExecutionOutcome noChange() {
            return new CronExecutionOutcome("no_change", false, false);
        }

        static CronExecutionOutcome failure(String status) {
            return new CronExecutionOutcome(status, true, false);
        }

        boolean resetFailureStreak() {
            return !failed;
        }
    }

    private static String backgroundTargetSessionId(CronJobEntity job) {
        if (job.getAttachedSessionId() != null) {
            return job.getAttachedSessionId().toString();
        }
        if (job.isContinuityEnabled() && job.getLastRunSessionId() != null) {
            return job.getLastRunSessionId().toString();
        }
        return null;
    }

    public static String jobProfile(CronJobEntity job) {
        if (job == null || isBlank(job.getProfile())) {
            return DEFAULT_PROFILE;
        }
        return normalizeProfileForStorage(job.getProfile());
    }

    private static String normalizeProfileForStorage(String profile) {
        String value = profile == null || profile.isBlank()
            ? DEFAULT_PROFILE
            : profile.trim().toLowerCase(Locale.ROOT);
        if (DEFAULT_PROFILE.equals(value)) {
            return DEFAULT_PROFILE;
        }
        if (!PROFILE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Invalid profile name '" + value + "'. Must match [a-z0-9][a-z0-9_-]{0,63}");
        }
        return value;
    }

    private ModelSnapshot computeProviderModelSnapshots(
        String profile,
        String modelProvider,
        String modelName,
        String baseUrl,
        boolean noAgent
    ) {
        if (noAgent) {
            return new ModelSnapshot(null, null);
        }
        String providerSnapshot = isBlank(modelProvider)
            ? cleanModelSnapshot(resolveCurrentCronProvider(profile, baseUrl), true)
            : null;
        String modelSnapshot = isBlank(modelName)
            ? cleanModelSnapshot(resolveCurrentCronModel(profile), false)
            : null;
        return new ModelSnapshot(providerSnapshot, modelSnapshot);
    }

    private String resolveCurrentCronProvider(String profile, String baseUrl) {
        Map<String, Object> config = profileConfig(profile);
        Map<String, Object> cron = configMap(config.get("cron"));
        String cronProvider = configText(cron.get("model_provider"));
        if (cronProvider != null) {
            return cronProvider;
        }
        Map<String, Object> model = configMap(config.get("model"));
        String provider = configText(model.get("provider"));
        if (provider != null) {
            return provider;
        }
        if (!isBlank(baseUrl)) {
            return "custom";
        }
        AgentProperties.ModelProperties propertiesModel = properties.getModel();
        return propertiesModel != null ? propertiesModel.getProvider() : null;
    }

    private String resolveCurrentCronModel(String profile) {
        Map<String, Object> config = profileConfig(profile);
        Map<String, Object> cron = configMap(config.get("cron"));
        String cronModel = configText(cron.get("model"));
        if (cronModel != null) {
            return cronModel;
        }
        Object rawModel = config.get("model");
        if (rawModel instanceof String text) {
            String cleaned = configText(text);
            if (cleaned != null) {
                return cleaned;
            }
        }
        Map<String, Object> model = configMap(rawModel);
        String configured = firstConfigText(model.get("default"), model.get("model"), model.get("name"));
        if (configured != null) {
            return configured;
        }
        AgentProperties.ModelProperties propertiesModel = properties.getModel();
        String fallback = propertiesModel != null ? configText(propertiesModel.getModelName()) : null;
        if (fallback != null) {
            return fallback;
        }
        AgentProperties.ApiProperties api = properties.getApi();
        return api != null ? configText(api.getModelName()) : null;
    }

    private Map<String, Object> profileConfig(String profile) {
        if (profileService == null) {
            return Map.of();
        }
        try {
            return profileService.readConfig(profile);
        } catch (Exception e) {
            log.debug("Cron model snapshot config read skipped for profile {}", profile, e);
            return Map.of();
        }
    }

    private void enforceCronModelDriftGuard(CronJobEntity job) {
        String profile = jobProfile(job);
        Map<String, Object> config = profileConfig(profile);
        if (!cronModelDriftGuardEnabled(config)) {
            return;
        }
        String currentProvider = cleanModelSnapshot(resolveCurrentCronProvider(profile, job.getBaseUrl()), true);
        String currentModel = cleanModelSnapshot(resolveCurrentCronModel(profile), true);
        List<String> changes = new ArrayList<>();
        for (String axis : cronModelDriftAxes(job, currentProvider, currentModel, config)) {
            String snapshot = "provider".equals(axis)
                ? cleanModelSnapshot(job.getProviderSnapshot(), true)
                : cleanModelSnapshot(job.getModelSnapshot(), true);
            String current = "provider".equals(axis) ? currentProvider : currentModel;
            changes.add(axis + " '" + snapshot + "' -> '" + current + "'");
        }
        if (changes.isEmpty()) {
            return;
        }
        String remediation = "Pin this cron job explicitly with provider/model, or restore the original config.";
        throw new IllegalStateException(
            "Skipped to prevent unintended spend: global inference config drifted since this job was created ("
                + String.join("; ", changes)
                + "), and this job is unpinned. No inference call was made. "
                + remediation);
    }

    private static boolean cronModelDriftGuardEnabled(Map<String, Object> config) {
        Map<String, Object> cron = configMap(config.get("cron"));
        return cron.get("model_drift_guard") != Boolean.FALSE;
    }

    private static List<String> cronModelDriftAxes(CronJobEntity job,
                                                   String currentProvider,
                                                   String currentModel,
                                                   Map<String, Object> config) {
        List<String> axes = new ArrayList<>();
        if (cronAxisDrifted(
            "provider",
            job.getModelProvider(),
            job.getProviderSnapshot(),
            currentProvider,
            config)) {
            axes.add("provider");
        }
        if (cronAxisDrifted(
            "model",
            job.getModelName(),
            job.getModelSnapshot(),
            currentModel,
            config)) {
            axes.add("model");
        }
        return axes;
    }

    private static boolean cronAxisDrifted(String axis,
                                           String pinnedValue,
                                           String snapshotValue,
                                           String currentValue,
                                           Map<String, Object> config) {
        if (cronFleetDefaultCoversAxis(axis, config) || !isBlank(pinnedValue)) {
            return false;
        }
        String snapshot = cleanModelSnapshot(snapshotValue, true);
        String current = cleanModelSnapshot(currentValue, true);
        return snapshot != null && current != null && !snapshot.equals(current);
    }

    private static boolean cronFleetDefaultCoversAxis(String axis, Map<String, Object> config) {
        Map<String, Object> cron = configMap(config.get("cron"));
        String key = "model".equals(axis) ? "model" : "model_provider";
        return configText(cron.get(key)) != null;
    }

    private static Map<String, Object> configMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static String firstConfigText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = configText(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String configText(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String cleaned = text.trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String cleanModelSnapshot(String value, boolean lowerCase) {
        String cleaned = configText(value);
        if (cleaned == null) {
            return null;
        }
        return lowerCase ? cleaned.toLowerCase(Locale.ROOT) : cleaned;
    }

    private static boolean inferenceAxesChanged(
        String previousModelProvider,
        String previousModelName,
        String previousBaseUrl,
        boolean previousNoAgent,
        String currentModelProvider,
        String currentModelName,
        String currentBaseUrl,
        boolean currentNoAgent
    ) {
        return previousNoAgent != currentNoAgent
            || !Objects.equals(cleanModelField(previousModelProvider), cleanModelField(currentModelProvider))
            || !Objects.equals(cleanModelField(previousModelName), cleanModelField(currentModelName))
            || !Objects.equals(cleanBaseUrlField(previousBaseUrl), cleanBaseUrlField(currentBaseUrl));
    }

    private static String cleanModelField(String value) {
        String cleaned = configText(value);
        return cleaned == null ? null : cleaned;
    }

    private static String cleanBaseUrlField(String value) {
        String cleaned = configText(value);
        return cleaned == null ? null : cleaned.replaceAll("/+$", "");
    }

    private record ModelSnapshot(String provider, String model) {}

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
    private ScriptRunResult executeNoAgentJob(CronJobEntity job) {
        ScriptRunResult result = executeCronScript(job, job.getScript(), "no_agent", 120);
        if (!result.success()) {
            return result;
        }
        String stdoutStr = result.output() == null ? "" : result.output().trim();
        if (stdoutStr.isEmpty()) {
            log.info("Cron job '{}' (no_agent): empty stdout — silent run", job.getName());
        } else {
            log.info("Cron job '{}' (no_agent): script output ({} chars) delivered verbatim",
                job.getName(), stdoutStr.length());
            if (job.getDeliverTo() != null && !job.getDeliverTo().isBlank()) {
                log.info("Cron job '{}' (no_agent): delivering to: {}", job.getName(), job.getDeliverTo());
            }
        }
        return result;
    }

    private ScriptRunResult executeCronScript(CronJobEntity job, String scriptPath, String purpose, int timeoutSeconds) {
        if (scriptPath == null || scriptPath.isBlank()) {
            String error = "no_agent".equals(purpose)
                ? NO_AGENT_WITHOUT_SCRIPT_ERROR
                : "monitor requires a script or http(s) URL";
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }

        Path scriptFile;
        try {
            scriptFile = resolveCronScriptFile(scriptPath);
        } catch (IllegalArgumentException e) {
            String error = e.getMessage();
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }

        if (scriptFile == null) {
            String error = "Script is required for cron " + purpose + " execution.";
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }
        if (!Files.exists(scriptFile)) {
            String error = "Script not found: " + scriptFile.toAbsolutePath().normalize();
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }
        try {
            Path scriptsDir = resolveCronScriptsDir().toRealPath();
            Path realScriptFile = scriptFile.toRealPath();
            if (!realScriptFile.startsWith(scriptsDir)) {
                String error = "Blocked: script path resolves outside the scripts directory ("
                    + scriptsDir + "): " + scriptPath;
                log.error("Cron job '{}': {}", job.getName(), error);
                return ScriptRunResult.failure(error);
            }
            scriptFile = realScriptFile;
        } catch (Exception e) {
            String error = "Blocked: script path is not a valid filesystem path: " + scriptPath;
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }
        if (!Files.isRegularFile(scriptFile)) {
            String error = "Script path is not a file: " + scriptFile.toAbsolutePath().normalize();
            log.error("Cron job '{}': {}", job.getName(), error);
            return ScriptRunResult.failure(error);
        }

        String ext = scriptFile.getFileName().toString();
        int dotIdx = ext.lastIndexOf('.');
        String suffix = dotIdx >= 0 ? ext.substring(dotIdx).toLowerCase() : "";
        String scriptAbsolutePath = scriptFile.toAbsolutePath().normalize().toString();
        List<String> command = new ArrayList<>();
        if (".sh".equals(suffix) || ".bash".equals(suffix)) {
            command.add("bash");
            command.add(scriptAbsolutePath);
        } else {
            command.add("python3");
            command.add(scriptAbsolutePath);
        }

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
            java.util.concurrent.CompletableFuture<String> stdoutFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> readUtf8Stream(process.getInputStream()));
            java.util.concurrent.CompletableFuture<String> stderrFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> readUtf8Stream(process.getErrorStream()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String error = "Script timed out after " + timeoutSeconds + "s: " + scriptAbsolutePath;
                log.error("Cron job '{}': {}", job.getName(), error);
                return ScriptRunResult.failure(error);
            }

            int exitCode = process.exitValue();
            String stdoutStr = stdoutFuture.get(5, TimeUnit.SECONDS).trim();
            String stderrStr = stderrFuture.get(5, TimeUnit.SECONDS).trim();

            if (exitCode != 0) {
                String error = "Script failed with exit " + exitCode
                    + "\nstdout: " + stdoutStr
                    + "\nstderr: " + stderrStr;
                log.error("Cron job '{}' ({}): script failed (exit {})\nstdout: {}\nstderr: {}",
                    job.getName(), purpose, exitCode, stdoutStr, stderrStr);
                return ScriptRunResult.failure(error);
            }
            return ScriptRunResult.ok(stdoutStr);
        } catch (UncheckedIOException e) {
            String error = "Script output read failed: " + e.getCause().getMessage();
            log.error("Cron job '{}' ({}): {}", job.getName(), purpose, error);
            return ScriptRunResult.failure(error);
        } catch (Exception e) {
            String error = "Script execution failed: " + e.getMessage();
            log.error("Cron job '{}' ({}): {}", job.getName(), purpose, error);
            return ScriptRunResult.failure(error);
        }
    }

    private MonitorOutcome evaluateMonitor(CronJobEntity job) {
        String monitor = job.getMonitor();
        if (monitor == null || monitor.isBlank()) {
            return MonitorOutcome.notConfigured();
        }

        MonitorSourceResult source = runMonitorSource(job, monitor);
        if (!source.success()) {
            return MonitorOutcome.failure(source.error());
        }

        String output = source.output() == null ? "" : source.output();
        String outputHash = hashMonitorOutput(output);
        if (outputHash.equals(job.getMonitorLastHash())) {
            return MonitorOutcome.unchanged();
        }

        String contextBlock = buildMonitorContextBlock(source.source(), job.getMonitorLastOutput(), output);
        return MonitorOutcome.changed(outputHash, output, Instant.now(), contextBlock);
    }

    private MonitorSourceResult runMonitorSource(CronJobEntity job, String monitor) {
        if (isMonitorUrl(monitor)) {
            return fetchMonitorUrl(monitor);
        }
        ScriptRunResult result = executeCronScript(job, monitor, "monitor", 120);
        return result.success()
            ? MonitorSourceResult.success("script:" + monitor, result.output())
            : MonitorSourceResult.failure(result.error());
    }

    private MonitorSourceResult fetchMonitorUrl(String url) {
        try {
            validateMonitorUrl(url);
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(MONITOR_URL_TIMEOUT_SECONDS))
                .header("Accept", "text/plain,application/json,*/*;q=0.5")
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_MONITOR_URL_BYTES + 1);
            }
            if (body.length > MAX_MONITOR_URL_BYTES) {
                return MonitorSourceResult.failure(
                    "Monitor URL response exceeded " + MAX_MONITOR_URL_BYTES + " bytes: " + url);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return MonitorSourceResult.failure(
                    "Monitor URL returned HTTP " + response.statusCode() + ": " + url);
            }
            return MonitorSourceResult.success("url:" + url, new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return MonitorSourceResult.failure("Monitor URL read failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MonitorSourceResult.failure("Monitor URL read interrupted: " + url);
        } catch (Exception e) {
            return MonitorSourceResult.failure("Monitor URL read failed: " + e.getMessage());
        }
    }

    private String normalizeMonitorSource(String monitor) {
        if (monitor == null) {
            return null;
        }
        String raw = monitor.trim();
        if (raw.isBlank()) {
            return null;
        }
        if (isMonitorUrl(raw)) {
            validateMonitorUrl(raw);
            return raw;
        }
        return normalizeCronScriptPath(raw);
    }

    private void validateMonitorUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid monitor_url: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("monitor_url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("monitor_url must include a host");
        }
        if (!new DefaultUrlSafety(properties).isUrlAllowed(url)) {
            throw new IllegalArgumentException("monitor_url blocked by safety policy: " + uri.getHost());
        }
    }

    private static String applyContinuity(String contextFrom, boolean continuityEnabled) {
        List<String> values = new ArrayList<>();
        if (contextFrom != null && !contextFrom.isBlank()) {
            for (String part : contextFrom.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !"self".equals(trimmed) && !values.contains(trimmed)) {
                    values.add(trimmed);
                }
            }
        }
        if (continuityEnabled) {
            values.add(0, "self");
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static boolean isMonitorUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String hashMonitorOutput(String output) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((output == null ? "" : output).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String buildMonitorContextBlock(String source, String previousOutput, String currentOutput) {
        StringBuilder block = new StringBuilder();
        block.append("=== Cron monitor changed ===\n");
        block.append("Monitor source: ").append(source).append("\n\n");
        if (previousOutput == null) {
            block.append("Previous output: <none>\n\n");
        } else {
            block.append("Previous output (truncated):\n")
                .append(truncateMonitorBlock(previousOutput, MAX_MONITOR_OUTPUT_CHARS))
                .append("\n\n");
        }
        block.append("Current output (truncated):\n")
            .append(truncateMonitorBlock(currentOutput, MAX_MONITOR_OUTPUT_CHARS));
        String diff = buildMonitorDiff(previousOutput, currentOutput);
        if (!diff.isBlank()) {
            block.append("\n\nDiff (truncated):\n").append(diff);
        }
        return block.toString();
    }

    private static String buildMonitorDiff(String previousOutput, String currentOutput) {
        if (previousOutput == null || previousOutput.equals(currentOutput)) {
            return "";
        }
        List<String> previousLines = previousOutput.lines().toList();
        List<String> currentLines = currentOutput == null ? List.of() : currentOutput.lines().toList();
        StringBuilder diff = new StringBuilder();
        int max = Math.max(previousLines.size(), currentLines.size());
        for (int i = 0; i < max; i++) {
            String previous = i < previousLines.size() ? previousLines.get(i) : null;
            String current = i < currentLines.size() ? currentLines.get(i) : null;
            if (java.util.Objects.equals(previous, current)) {
                continue;
            }
            if (previous != null) {
                diff.append("- ").append(previous).append("\n");
            }
            if (current != null) {
                diff.append("+ ").append(current).append("\n");
            }
            if (diff.length() >= MAX_MONITOR_DIFF_CHARS) {
                return diff.substring(0, MAX_MONITOR_DIFF_CHARS) + "\n... diff truncated ...";
            }
        }
        return diff.toString().trim();
    }

    private static String truncateMonitorBlock(String value, int maxChars) {
        String text = value == null ? "" : value;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... output truncated ...";
    }

    private static String capStoredMonitorOutput(String value) {
        return truncateMonitorBlock(value, MAX_MONITOR_STORED_CHARS);
    }

    private static String readUtf8Stream(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalizeCronScriptPath(String script) {
        if (script == null) {
            return null;
        }
        String raw = script.trim();
        if (raw.isBlank()) {
            return null;
        }
        String normalizedSeparators = raw.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.startsWith("~")
            || (normalizedSeparators.length() >= 2 && normalizedSeparators.charAt(1) == ':')) {
            throw new IllegalArgumentException("Script path must be relative to ~/.hermes/scripts/. "
                + "Got absolute or home-relative path: " + raw + ". "
                + "Place scripts in ~/.hermes/scripts/ and use just the filename.");
        }

        Path relative;
        try {
            relative = Path.of(normalizedSeparators).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid script path: " + raw);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Script path must be relative to ~/.hermes/scripts/. "
                + "Got absolute or home-relative path: " + raw + ". "
                + "Place scripts in ~/.hermes/scripts/ and use just the filename.");
        }

        Path scriptsDir = resolveCronScriptsDir();
        Path resolved = scriptsDir.resolve(relative).normalize();
        if (!resolved.startsWith(scriptsDir)) {
            throw new IllegalArgumentException("Script path escapes the scripts directory via traversal: " + raw);
        }
        String stored = relative.toString().replace('\\', '/');
        if (stored.isBlank() || ".".equals(stored)) {
            throw new IllegalArgumentException("Script path must point to a file under ~/.hermes/scripts/.");
        }
        return stored;
    }

    private static Path resolveCronScriptFile(String script) {
        String normalized = normalizeCronScriptPath(script);
        return normalized == null
            ? null
            : resolveCronScriptsDir().resolve(Path.of(normalized)).normalize();
    }

    private static Path resolveCronScriptsDir() {
        String hermesHome = System.getenv("HERMES_HOME");
        if (hermesHome == null || hermesHome.isBlank()) {
            return Path.of(System.getProperty("user.home", "/root"), ".hermes", "scripts")
                .toAbsolutePath()
                .normalize();
        }
        return Path.of(hermesHome)
            .resolve("scripts")
            .toAbsolutePath()
            .normalize();
    }

    private static void validateRunnablePayload(CronJobEntity job) {
        if (job.isNoAgent()) {
            if (isBlank(job.getScript())) {
                throw new IllegalArgumentException(NO_AGENT_WITHOUT_SCRIPT_ERROR);
            }
            return;
        }
        if (isBlank(job.getPrompt()) && isBlank(job.getScript()) && isBlank(job.getSkills())) {
            throw new IllegalArgumentException(EMPTY_PAYLOAD_ERROR);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ScriptRunResult(boolean success, String error, String output) {
        static ScriptRunResult ok(String output) {
            return new ScriptRunResult(true, null, output);
        }

        static ScriptRunResult failure(String error) {
            return new ScriptRunResult(false, error == null || error.isBlank() ? "Script failed" : error, null);
        }
    }

    private record MonitorSourceResult(boolean success, String source, String output, String error) {
        static MonitorSourceResult success(String source, String output) {
            return new MonitorSourceResult(true, source, output == null ? "" : output, null);
        }

        static MonitorSourceResult failure(String error) {
            return new MonitorSourceResult(false, null, null,
                error == null || error.isBlank() ? "Monitor source failed" : error);
        }
    }

    private record MonitorOutcome(
        boolean configured,
        boolean changed,
        boolean success,
        String error,
        String hash,
        String output,
        Instant changedAt,
        String contextBlock
    ) {
        static MonitorOutcome notConfigured() {
            return new MonitorOutcome(false, false, true, null, null, null, null, null);
        }

        static MonitorOutcome unchanged() {
            return new MonitorOutcome(true, false, true, null, null, null, null, null);
        }

        static MonitorOutcome changed(String hash, String output, Instant changedAt, String contextBlock) {
            return new MonitorOutcome(true, true, true, null, hash, output, changedAt, contextBlock);
        }

        static MonitorOutcome failure(String error) {
            return new MonitorOutcome(true, false, false,
                error == null || error.isBlank() ? "Monitor source failed" : error,
                null, null, null, null);
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
            if ("self".equalsIgnoreCase(trimmed)) {
                continue;
            }
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
