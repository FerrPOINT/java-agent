package com.azhukov.agent.api;

import com.azhukov.agent.core.security.CronPromptScanner;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/jobs", "/p/{profile}/api/jobs"})
public class HermesCronJobsController {

    private static final Pattern HERMES_JOB_ID_PATTERN = Pattern.compile("[a-f0-9]{12}");
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_PROMPT_LENGTH = 5_000;
    private static final Set<String> UPDATE_ALLOWED_FIELDS = Set.of(
        "name", "schedule", "prompt", "deliver", "skills", "skill", "repeat", "enabled",
        "monitor", "monitor_script", "monitor_url", "continuity", "attach_to_session", "attached_session_id"
    );

    private final CronJobService cronJobService;
    private final ProfileService profileService;

    public HermesCronJobsController(CronJobService cronJobService, ProfileService profileService) {
        this.cronJobService = cronJobService;
        this.profileService = profileService;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable(name = "profile", required = false) String pathProfile,
                                    @RequestParam(name = "include_disabled", required = false) String includeDisabled) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        List<CronJobEntity> jobs = profile == null
            ? cronJobService.list(truthy(includeDisabled))
            : cronJobService.listForProfile(profile, truthy(includeDisabled));
        return Map.of("jobs", jobs.stream()
            .map(HermesCronJobsController::hermesJob)
            .toList());
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable(name = "profile", required = false) String pathProfile,
                                      @RequestBody JobCreateBody body) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        String name = requiredTrimmed(body.name(), "Name is required");
        String schedule = requiredTrimmed(body.schedule(), "Schedule is required");
        String prompt = body.prompt() == null ? "" : body.prompt();
        String skills = canonicalSkills(body.skill(), body.skills());
        Integer repeat = repeatForCreate(body.repeat());
        String monitor = monitorSource(body.monitor(), body.monitorScript(), body.monitorUrl());
        UUID attachedSessionId = attachedSessionId(body.attachToSession(), body.attachedSessionId());
        validateLengths(name, prompt);
        scanPrompt(prompt);

        boolean hasHermesExtras = monitor != null || body.continuity() != null || body.attachToSession() != null;
        CronJobEntity entity;
        if (hasHermesExtras && profile != null) {
            entity = cronJobService.createInProfile(
                profile,
                name,
                schedule,
                prompt,
                defaultIfBlank(body.deliver(), "local"),
                skills,
                null,
                repeat,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                monitor,
                Boolean.TRUE.equals(body.continuity()),
                attachedSessionId);
        } else if (hasHermesExtras) {
            entity = cronJobService.create(
                name,
                schedule,
                prompt,
                defaultIfBlank(body.deliver(), "local"),
                skills,
                null,
                repeat,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                monitor,
                Boolean.TRUE.equals(body.continuity()),
                attachedSessionId);
        } else if (profile != null) {
            entity = cronJobService.createInProfile(
                profile,
                name,
                schedule,
                prompt,
                defaultIfBlank(body.deliver(), "local"),
                skills,
                null,
                repeat,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        } else {
            entity = cronJobService.create(
                name,
                schedule,
                prompt,
                defaultIfBlank(body.deliver(), "local"),
                skills,
                null,
                repeat,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        }
        return jobEnvelope(entity);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable(name = "profile", required = false) String pathProfile,
                                                   @PathVariable String jobId) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        return findJob(jobId, profile)
            .map(entity -> ResponseEntity.ok(jobEnvelope(entity)))
            .orElseGet(HermesCronJobsController::notFound);
    }

    @PatchMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable(name = "profile", required = false) String pathProfile,
                                                      @PathVariable String jobId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        Optional<CronJobEntity> existing = findJob(jobId, profile);
        if (existing.isEmpty()) {
            return notFound();
        }
        UUID id = existing.get().getId();
        Map<String, Object> sanitized = sanitizeUpdate(body == null ? Map.of() : body);
        if (sanitized.isEmpty()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "No valid fields to update");
        }

        String name = optionalString(sanitized, "name");
        String schedule = optionalString(sanitized, "schedule");
        String prompt = optionalString(sanitized, "prompt");
        if (name != null || prompt != null) {
            validateLengths(name, prompt);
        }
        if (prompt != null) {
            scanPrompt(prompt);
        }

        boolean hasHermesExtras = containsAny(sanitized,
            "monitor", "monitor_script", "monitor_url", "continuity", "attach_to_session");
        CronJobEntity entity = hasHermesExtras
            ? cronJobService.update(
                id,
                name,
                schedule,
                prompt,
                firstString(sanitized, "deliver"),
                optionalBoolean(sanitized, "enabled"),
                firstSkills(sanitized),
                null,
                firstInteger(sanitized, "repeat"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                monitorSource(sanitized),
                optionalBoolean(sanitized, "continuity"),
                optionalBoolean(sanitized, "attach_to_session"),
                attachedSessionIdForUpdate(sanitized))
            : cronJobService.update(
                id,
                name,
                schedule,
                prompt,
                firstString(sanitized, "deliver"),
                optionalBoolean(sanitized, "enabled"),
                firstSkills(sanitized),
                null,
                firstInteger(sanitized, "repeat"),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        return ResponseEntity.ok(jobEnvelope(entity));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable(name = "profile", required = false) String pathProfile,
                                                      @PathVariable String jobId) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        Optional<CronJobEntity> existing = findJob(jobId, profile);
        if (existing.isEmpty()) {
            return notFound();
        }
        cronJobService.remove(existing.get().getId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{jobId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable(name = "profile", required = false) String pathProfile,
                                                     @PathVariable String jobId) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        Optional<CronJobEntity> existing = findJob(jobId, profile);
        if (existing.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(jobEnvelope(cronJobService.pause(existing.get().getId())));
    }

    @PostMapping("/{jobId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable(name = "profile", required = false) String pathProfile,
                                                      @PathVariable String jobId) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        Optional<CronJobEntity> existing = findJob(jobId, profile);
        if (existing.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(jobEnvelope(cronJobService.resume(existing.get().getId())));
    }

    @PostMapping("/{jobId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable(name = "profile", required = false) String pathProfile,
                                                   @PathVariable String jobId) {
        String profile = pathProfile == null ? null : resolveProfileScope(pathProfile);
        Optional<CronJobEntity> existing = findJob(jobId, profile);
        if (existing.isEmpty()) {
            return notFound();
        }
        CronJobEntity job = existing.get();
        boolean inline = job.isNoAgent();
        CronJobEntity entity = inline
            ? cronJobService.runNow(job.getId())
            : cronJobService.runNowBackground(job.getId(), null);
        return ResponseEntity.ok(runEnvelope(entity, inline ? "inline" : "background"));
    }

    public record JobCreateBody(
        String name,
        String schedule,
        String prompt,
        String deliver,
        Object skills,
        String skill,
        Object repeat,
        String monitor,
        @com.fasterxml.jackson.annotation.JsonProperty("monitor_script") String monitorScript,
        @com.fasterxml.jackson.annotation.JsonProperty("monitor_url") String monitorUrl,
        Boolean continuity,
        @com.fasterxml.jackson.annotation.JsonProperty("attach_to_session") Boolean attachToSession,
        @com.fasterxml.jackson.annotation.JsonProperty("attached_session_id") String attachedSessionId
    ) {}

    private static Map<String, Object> jobEnvelope(CronJobEntity entity) {
        return Map.of("job", hermesJob(entity));
    }

    private static Map<String, Object> runEnvelope(CronJobEntity entity, String executionMode) {
        Map<String, Object> job = hermesJob(entity);
        job.put("execution_mode", executionMode);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("job", job);
        envelope.put("execution_mode", executionMode);
        return envelope;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
    }

    private Optional<CronJobEntity> findJob(String jobId, String profile) {
        UUID uuid = parseUuidJobId(jobId);
        if (uuid != null) {
            return profile == null ? cronJobService.findById(uuid) : cronJobService.findById(uuid, profile);
        }
        if (!HERMES_JOB_ID_PATTERN.matcher(jobId).matches()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Invalid job ID format");
        }
        List<CronJobEntity> candidates = profile == null
            ? cronJobService.list(true)
            : cronJobService.listForProfile(profile, true);
        List<CronJobEntity> matches = candidates.stream()
            .filter(entity -> jobId.equals(hermesJobId(entity.getId())))
            .toList();
        if (matches.size() > 1) {
            throw new AgentException(HttpStatus.CONFLICT, "Ambiguous job ID");
        }
        return matches.stream().findFirst();
    }

    private String resolveProfileScope(String rawProfile) {
        try {
            String profile = profileService.normalizeProfileName(rawProfile);
            profileService.validateProfileName(profile);
            if (!profileService.knownProfile(profile)) {
                throw new AgentException(HttpStatus.NOT_FOUND, "Unknown profile: " + profile);
            }
            return profile;
        } catch (IllegalArgumentException e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static UUID parseUuidJobId(String jobId) {
        try {
            return UUID.fromString(jobId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> hermesJob(CronJobEntity entity) {
        List<String> skills = splitCsv(entity.getSkills());
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", hermesJobId(entity.getId()));
        job.put("profile", CronJobService.jobProfile(entity));
        job.put("name", entity.getName());
        job.put("prompt", entity.getPrompt());
        job.put("skills", skills);
        job.put("skill", skills == null || skills.isEmpty() ? null : skills.get(0));
        job.put("model", entity.getModelName());
        job.put("provider", entity.getModelProvider());
        job.put("base_url", entity.getBaseUrl());
        job.put("provider_snapshot", entity.getProviderSnapshot());
        job.put("model_snapshot", entity.getModelSnapshot());
        job.put("script", entity.getScript());
        job.put("no_agent", entity.isNoAgent());
        job.put("monitor", entity.getMonitor());
        if (entity.getMonitor() != null && !entity.getMonitor().isBlank()) {
            if (isMonitorUrl(entity.getMonitor())) {
                job.put("monitor_url", entity.getMonitor());
            } else {
                job.put("monitor_script", entity.getMonitor());
            }
        }
        job.put("monitor_last_hash", entity.getMonitorLastHash());
        job.put("monitor_last_output", entity.getMonitorLastOutput());
        job.put("monitor_last_changed_at", entity.getMonitorLastChangedAt());
        job.put("continuity", entity.isContinuityEnabled());
        job.put("attach_to_session", entity.getAttachedSessionId() != null);
        job.put("attached_session_id", entity.getAttachedSessionId());
        job.put("context_from", splitCsv(entity.getContextFrom()));
        job.put("schedule", entity.getSchedule());
        job.put("schedule_display", entity.getSchedule());
        job.put("repeat", repeatState(entity));
        job.put("enabled", entity.isEnabled());
        job.put("state", entity.isEnabled() ? "scheduled" : "paused");
        job.put("paused_at", null);
        job.put("paused_reason", null);
        job.put("created_at", entity.getCreatedAt());
        job.put("next_run_at", entity.getNextRunAt());
        job.put("last_run_at", entity.getLastRunAt());
        job.put("last_status", entity.getLastStatus());
        job.put("last_error", entity.getLastError());
        job.put("last_delivery_error", null);
        job.put("failure_streak", entity.getConsecutiveFailures());
        job.put("deliver", entity.getDeliverTo());
        job.put("origin", null);
        job.put("enabled_toolsets", splitCsv(entity.getEnabledToolsets()));
        job.put("workdir", entity.getWorkdir());
        job.put("last_delivered_run_at", entity.getLastDeliveredRunAt());
        job.put("last_run_session_id", entity.getLastRunSessionId());
        return job;
    }

    private static String hermesJobId(UUID id) {
        if (id == null) {
            return null;
        }
        return id.toString().replace("-", "").substring(0, 12);
    }

    private static Map<String, Object> repeatState(CronJobEntity entity) {
        Map<String, Object> repeat = new LinkedHashMap<>();
        repeat.put("times", entity.getRepeatCount());
        repeat.put("completed", entity.getRepeatCompleted());
        return repeat;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }

    private static boolean truthy(String value) {
        return value != null && Set.of("true", "1").contains(value.toLowerCase());
    }

    private static Map<String, Object> sanitizeUpdate(Map<String, Object> body) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (UPDATE_ALLOWED_FIELDS.contains(key)) {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }

    private static String requiredTrimmed(String value, String error) {
        if (value == null || value.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, error);
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void validateLengths(String name, String prompt) {
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Name must be ≤ " + MAX_NAME_LENGTH + " characters");
        }
        if (prompt != null && prompt.length() > MAX_PROMPT_LENGTH) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Prompt must be ≤ " + MAX_PROMPT_LENGTH + " characters");
        }
    }

    private static void scanPrompt(String prompt) {
        String scanError = CronPromptScanner.scan(prompt);
        if (!scanError.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, scanError);
        }
    }

    private static String monitorSource(String monitor, String monitorScript, String monitorUrl) {
        if (monitor != null) {
            return monitor.trim();
        }
        boolean hasScript = monitorScript != null;
        boolean hasUrl = monitorUrl != null;
        if (!hasScript && !hasUrl) {
            return null;
        }
        String scriptValue = monitorScript == null ? null : monitorScript.trim();
        String urlValue = monitorUrl == null ? null : monitorUrl.trim();
        if (hasScript && hasUrl && !isBlank(scriptValue) && !isBlank(urlValue)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Use only one of monitor, monitor_script, or monitor_url");
        }
        if (hasUrl) {
            if (!isBlank(urlValue) && !isMonitorUrl(urlValue)) {
                throw new AgentException(HttpStatus.BAD_REQUEST, "monitor_url must use http or https");
            }
            return urlValue == null ? "" : urlValue;
        }
        return scriptValue == null ? "" : scriptValue;
    }

    private static String monitorSource(Map<String, Object> body) {
        if (!containsAny(body, "monitor", "monitor_script", "monitor_url")) {
            return null;
        }
        return monitorSource(
            optionalString(body, "monitor"),
            optionalString(body, "monitor_script"),
            optionalString(body, "monitor_url"));
    }

    private static UUID attachedSessionId(Boolean attachToSession, String attachedSessionId) {
        if (!Boolean.TRUE.equals(attachToSession)) {
            return null;
        }
        if (attachedSessionId == null || attachedSessionId.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST,
                "attach_to_session=true requires attached_session_id on REST requests");
        }
        return parseAttachedSessionId(attachedSessionId);
    }

    private static UUID attachedSessionIdForUpdate(Map<String, Object> body) {
        Boolean attach = optionalBoolean(body, "attach_to_session");
        if (attach == null || !attach) {
            return null;
        }
        String attachedSessionId = optionalString(body, "attached_session_id");
        if (attachedSessionId == null || attachedSessionId.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST,
                "attach_to_session=true requires attached_session_id on REST requests");
        }
        return parseAttachedSessionId(attachedSessionId);
    }

    private static UUID parseAttachedSessionId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "attached_session_id must be a UUID");
        }
    }

    private static boolean containsAny(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMonitorUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Integer repeatForCreate(Object repeat) {
        if (repeat == null) {
            return null;
        }
        if (!(repeat instanceof Integer value) || value < 1) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Repeat must be a positive integer");
        }
        return value;
    }

    private static String firstString(Map<String, Object> body, String... names) {
        for (String name : names) {
            String value = optionalString(body, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String optionalString(Map<String, Object> body, String name) {
        if (!body.containsKey(name) || body.get(name) == null) {
            return null;
        }
        Object value = body.get(name);
        if (!(value instanceof String string)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, name + " must be a string");
        }
        return string;
    }

    private static Boolean optionalBoolean(Map<String, Object> body, String name) {
        if (!body.containsKey(name) || body.get(name) == null) {
            return null;
        }
        Object value = body.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && Set.of("true", "false").contains(string.toLowerCase())) {
            return Boolean.parseBoolean(string);
        }
        throw new AgentException(HttpStatus.BAD_REQUEST, name + " must be a boolean");
    }

    private static Integer firstInteger(Map<String, Object> body, String... names) {
        for (String name : names) {
            Integer value = optionalInteger(body, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer optionalInteger(Map<String, Object> body, String name) {
        if (!body.containsKey(name) || body.get(name) == null) {
            return null;
        }
        Object value = body.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException e) {
                throw new AgentException(HttpStatus.BAD_REQUEST, name + " must be an integer");
            }
        }
        throw new AgentException(HttpStatus.BAD_REQUEST, name + " must be an integer");
    }

    private static String firstSkills(Map<String, Object> body) {
        boolean hasSkills = body.containsKey("skills");
        boolean hasSkill = body.containsKey("skill");
        if (!hasSkills && !hasSkill) {
            return null;
        }
        String skills = canonicalSkills(
            hasSkill ? body.get("skill") : null,
            hasSkills ? body.get("skills") : null);
        return skills == null ? "" : skills;
    }

    private static String canonicalSkills(Object skill, Object skills) {
        List<String> normalized = new ArrayList<>();
        if (skills == null) {
            addSkill(normalized, skill);
        } else if (skills instanceof String string) {
            addSkill(normalized, string);
        } else if (skills instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !(item instanceof String)) {
                    throw new AgentException(HttpStatus.BAD_REQUEST, "skills must be a string or array of strings");
                }
                addSkill(normalized, item);
            }
        } else {
            throw new AgentException(HttpStatus.BAD_REQUEST, "skills must be a string or array of strings");
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private static void addSkill(List<String> normalized, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof String string)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "skill must be a string");
        }
        String text = string.trim();
        if (!text.isEmpty() && !normalized.contains(text)) {
            normalized.add(text);
        }
    }
}
