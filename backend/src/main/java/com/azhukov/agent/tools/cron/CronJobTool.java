package com.azhukov.agent.tools.cron;

import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.core.security.CronPromptScanner;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@AgentTool(
    name = "cronjob",
    description = "Manage scheduled cron jobs with a single compressed tool.\n\nUse action='create' to schedule a new job from a prompt or one or more skills.\nUse action='list' to inspect jobs.\nUse action='update', 'pause', 'resume', 'remove', or 'run' to manage an existing job.\n\naction='run' fires the job immediately in the BACKGROUND (like delegate_task): the call returns at once with a handle and the job's outcome re-enters the conversation as a new message when it finishes. Do not wait or poll after triggering a run — just continue. Optionally pass 'prompt' with action='run' to inject transient per-run context (appended to the job's stored prompt for that single fire only, never persisted).\n\nTo stop a job the user no longer wants: first action='list' to find the job_id, then action='remove' with that job_id. Never guess job IDs — always list first.\n\nJobs run in a fresh session with no current-chat context, so prompts must be self-contained.\nIf skills are provided on create, the future cron run loads those skills in order, then follows the prompt as the task instruction.\nOn update, passing skills=[] clears attached skills.\n\nNOTE: The agent's final response is auto-delivered to the target. Put the primary\nuser-facing content in the final response. Cron jobs run autonomously with no user\npresent — they cannot ask questions or request clarification.\n\nScheduling from cron-run sessions is disabled by default and enabled via cron.allow_agent_scheduling in config.yaml. When enabled, jobs created from a cron run are user-owned in the same flat job table as every other job, and their delivery resolves to the creating job's own persistent target — never to the ephemeral cron-run session. Prefer updating an existing job (list first, then update by job_id) over creating near-duplicates.",
    toolset = "cronjob"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class CronJobTool implements ToolHandler {

    private static final Pattern HERMES_JOB_ID_PATTERN = Pattern.compile("[a-f0-9]{12}");

    private final CronJobService cronJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        CronJobArgs args;
        try {
            args = ToolHandler.parseJson(arguments, CronJobArgs.class);
        } catch (IllegalArgumentException e) {
            return cronError(e.getMessage());
        }
        String action = args.action() == null ? "" : args.action().trim().toLowerCase();

        return switch (action) {
            case "create" -> createJob(args, session);
            case "list" -> listJobs();
            case "pause" -> pauseJob(args);
            case "resume" -> resumeJob(args);
            case "remove" -> removeJob(args);
            case "run" -> runJob(args);
            case "update" -> updateJob(args, session);
            default -> cronError("Unknown cron action '" + action + "'");
        };
    }

    private ToolResult createJob(CronJobArgs args, Session session) {
        if (args.schedule() == null || args.schedule().isBlank()) return cronError("schedule is required for create");
        boolean noAgent = Boolean.TRUE.equals(args.noAgent());
        boolean hasPrompt = args.prompt() != null && !args.prompt().isBlank();
        List<String> skillList;
        try {
            skillList = canonicalSkills(args.skill(), args.skills());
        } catch (IllegalArgumentException e) {
            return cronError(e.getMessage());
        }
        String skills = csvOrNull(skillList);
        boolean hasSkills = skills != null && !skills.isBlank();
        boolean hasScript = args.script() != null && !args.script().isBlank();
        if (noAgent && !hasScript) {
            return cronError("create with no_agent=True requires a script — the script is the job.");
        }
        if (!noAgent && !hasPrompt && !hasSkills) {
            return cronError("create requires either prompt or at least one skill");
        }
        String scanError = CronPromptScanner.scan(args.prompt());
        if (!scanError.isBlank()) return cronError(scanError);
        String contextFrom;
        String enabledToolsets;
        try {
            contextFrom = canonicalStringList(args.contextFrom(), false);
            enabledToolsets = canonicalStringList(args.enabledToolsets(), false);
        } catch (IllegalArgumentException e) {
            return cronError(e.getMessage());
        }
        MonitorInput monitor;
        try {
            monitor = resolveMonitorInput(args);
        } catch (IllegalArgumentException e) {
            return cronError(e.getMessage());
        }
        try {
            UUID attachedSessionId = attachedSessionId(args.attachToSession(), session);
            boolean hasHermesExtras = monitor.provided()
                || args.continuity() != null
                || args.attachToSession() != null;
            CronJobEntity entity = hasHermesExtras
                ? cronJobService.create(
                    defaultJobName(args.name(), args.prompt(), skillList), args.schedule(), args.prompt(), args.deliver(),
                     skills, contextFrom,
                     args.repeat(),
                     args.script(), args.noAgent() != null && args.noAgent(),
                     enabledToolsets, args.workdir(),
                     args.modelProvider(), args.modelName(), args.baseUrl(),
                     monitor.value(), Boolean.TRUE.equals(args.continuity()), attachedSessionId
                 )
                : cronJobService.create(
                    defaultJobName(args.name(), args.prompt(), skillList), args.schedule(), args.prompt(), args.deliver(),
                    skills, contextFrom,
                    args.repeat(),
                    args.script(), args.noAgent() != null && args.noAgent(),
                    enabledToolsets, args.workdir(),
                    args.modelProvider(), args.modelName(), args.baseUrl()
                );
            return cronOk(createEnvelope(entity));
        } catch (Exception e) {
            return cronError("Failed to create cron job: " + e.getMessage());
        }
    }

    private ToolResult listJobs() {
        try {
            List<CronJobEntity> jobs = cronJobService.list();
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (CronJobEntity job : jobs) {
                formatted.add(formatJob(job));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("count", formatted.size());
            result.put("jobs", formatted);
            return cronOk(result);
        } catch (Exception e) {
            return cronError("Failed to list cron jobs: " + e.getMessage());
        }
    }

    private ToolResult pauseJob(CronJobArgs args) {
        if (missingJobRef(args)) return cronError("job_id is required for action 'pause'");
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return cronError(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.pause(job.get().getId());
            return cronOk(jobEnvelope(entity));
        } catch (Exception e) {
            return cronError("Failed to pause cron job: " + e.getMessage());
        }
    }

    private ToolResult resumeJob(CronJobArgs args) {
        if (missingJobRef(args)) return cronError("job_id is required for action 'resume'");
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return cronError(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.resume(job.get().getId());
            return cronOk(jobEnvelope(entity));
        } catch (Exception e) {
            return cronError("Failed to resume cron job: " + e.getMessage());
        }
    }

    private ToolResult removeJob(CronJobArgs args) {
        if (missingJobRef(args)) return cronError("job_id is required for action 'remove'");
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return cronError(jobNotFoundMsg(args));
        try {
            cronJobService.remove(job.get().getId());
            Map<String, Object> removed = new LinkedHashMap<>();
            removed.put("id", hermesJobId(job.get().getId()));
            removed.put("uuid", job.get().getId() == null ? null : job.get().getId().toString());
            removed.put("name", job.get().getName());
            removed.put("schedule", job.get().getSchedule());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "Cron job '" + job.get().getName() + "' removed.");
            result.put("removed_job", removed);
            return cronOk(result);
        } catch (Exception e) {
            return cronError("Failed to remove cron job: " + e.getMessage());
        }
    }

    private ToolResult runJob(CronJobArgs args) {
        if (missingJobRef(args)) return cronError("job_id is required for action 'run'");
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return cronError(jobNotFoundMsg(args));
        String scanError = CronPromptScanner.scan(args.prompt());
        if (!scanError.isBlank()) return cronError(scanError);
        try {
            boolean inline = job.get().isNoAgent();
            CronJobEntity entity = inline
                ? cronJobService.runNow(job.get().getId(), args.prompt())
                : cronJobService.runNowBackground(job.get().getId(), args.prompt());
            Map<String, Object> formatted = formatJob(entity);
            formatted.put("executed", true);
            formatted.put("execution_mode", inline ? "inline" : "background");
            return cronOk(jobEnvelope(formatted));
        } catch (Exception e) {
            return cronError("Failed to run cron job: " + e.getMessage());
        }
    }

    private ToolResult updateJob(CronJobArgs args, Session session) {
        if (missingJobRef(args)) return cronError("job_id is required for action 'update'");
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return cronError(jobNotFoundMsg(args));
        String scanError = CronPromptScanner.scan(args.prompt());
        if (!scanError.isBlank()) return cronError(scanError);
        String skills;
        String contextFrom;
        String enabledToolsets;
        String name = args.name() != null && !args.name().isBlank() ? args.name() : null;
        boolean skillsProvided = args.skills() != null || args.skill() != null;
        boolean contextFromProvided = args.contextFrom() != null;
        boolean enabledToolsetsProvided = args.enabledToolsets() != null;
        MonitorInput monitor;
        try {
            skills = skillsProvided
                ? csvOrEmpty(canonicalSkills(args.skill(), args.skills()))
                : null;
            contextFrom = contextFromProvided ? canonicalStringList(args.contextFrom(), true) : null;
            enabledToolsets = enabledToolsetsProvided ? canonicalStringList(args.enabledToolsets(), true) : null;
            monitor = resolveMonitorInput(args);
        } catch (IllegalArgumentException e) {
            return cronError(e.getMessage());
        }
        if (!hasUpdates(args, name, skillsProvided, contextFromProvided, enabledToolsetsProvided, monitor.provided())) {
            return cronError("No updates provided.");
        }
        try {
            boolean hasHermesExtras = monitor.provided()
                || args.continuity() != null
                || args.attachToSession() != null;
            CronJobEntity entity = hasHermesExtras
                ? cronJobService.update(
                    job.get().getId(), name, args.schedule(), args.prompt(), args.deliver(), null,
                    skills, contextFrom,
                     args.repeat(),
                     args.script(), args.noAgent(),
                     enabledToolsets, args.workdir(),
                     args.modelProvider(), args.modelName(), args.baseUrl(),
                     monitor.provided() ? monitor.value() : null,
                     args.continuity(), args.attachToSession(), session == null ? null : session.id()
                 )
                : cronJobService.update(
                    job.get().getId(), name, args.schedule(), args.prompt(), args.deliver(), null,
                    skills, contextFrom,
                    args.repeat(),
                    args.script(), args.noAgent(),
                    enabledToolsets, args.workdir(),
                    args.modelProvider(), args.modelName(), args.baseUrl()
                );
            return cronOk(jobEnvelope(entity));
        } catch (Exception e) {
            return cronError("Failed to update cron job: " + e.getMessage());
        }
    }

    /**
     * Hermes parity: resolve a job by job_id first (as the tool description says),
     * falling back to name if job_id is absent.
     */
    private Optional<CronJobEntity> resolveJob(CronJobArgs args) {
        if (args.jobId() != null && !args.jobId().isBlank()) {
            String jobId = args.jobId().trim();
            try {
                return cronJobService.findById(java.util.UUID.fromString(jobId));
            } catch (IllegalArgumentException e) {
                if (!HERMES_JOB_ID_PATTERN.matcher(jobId).matches()) {
                    return Optional.empty();
                }
            }
            return cronJobService.list(true).stream()
                .filter(job -> jobId.equals(hermesJobId(job.getId())))
                .findFirst();
        }
        if (args.name() != null && !args.name().isBlank()) {
            return cronJobService.findByName(args.name());
        }
        return Optional.empty();
    }

    private static boolean missingJobRef(CronJobArgs args) {
        return (args.jobId() == null || args.jobId().isBlank())
            && (args.name() == null || args.name().isBlank());
    }

    private static String jobNotFoundMsg(CronJobArgs args) {
        if (args.jobId() != null && !args.jobId().isBlank()) {
            return "Job with ID or name '" + args.jobId() + "' not found. Use cronjob(action='list') to inspect jobs.";
        }
        return "Job with ID or name '" + args.name() + "' not found. Use cronjob(action='list') to inspect jobs.";
    }

    private static boolean hasUpdates(CronJobArgs args, String name, boolean skillsProvided,
                                      boolean contextFromProvided, boolean enabledToolsetsProvided,
                                      boolean monitorProvided) {
        return name != null
            || args.schedule() != null
            || args.prompt() != null
            || args.deliver() != null
            || skillsProvided
            || contextFromProvided
            || monitorProvided
            || args.repeat() != null
            || args.script() != null
            || args.noAgent() != null
            || args.continuity() != null
            || enabledToolsetsProvided
            || args.workdir() != null
            || args.attachToSession() != null
            || args.modelProvider() != null
            || args.modelName() != null
            || args.baseUrl() != null;
    }

    private ToolResult cronOk(Map<String, Object> payload) {
        return ToolResult.ok(toJson(payload));
    }

    private ToolResult cronError(String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.put("success", false);
        return new ToolResult(false, toJson(payload), error);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Failed to serialize cronjob result\",\"success\":false}";
        }
    }

    private Map<String, Object> createEnvelope(CronJobEntity entity) {
        Map<String, Object> job = formatJob(entity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("job_id", job.get("job_id"));
        result.put("name", job.get("name"));
        result.put("skill", job.get("skill"));
        result.put("skills", job.get("skills"));
        result.put("schedule", job.get("schedule"));
        result.put("repeat", job.get("repeat"));
        result.put("deliver", job.get("deliver"));
        result.put("next_run_at", job.get("next_run_at"));
        copyIfPresent(job, result, "monitor");
        copyIfPresent(job, result, "monitor_script");
        copyIfPresent(job, result, "monitor_url");
        copyIfPresent(job, result, "monitor_state");
        copyIfPresent(job, result, "continuity");
        copyIfPresent(job, result, "attach_to_session");
        copyIfPresent(job, result, "attached_session_id");
        result.put("job", job);
        result.put("message", "Cron job '" + job.get("name") + "' created.");
        return result;
    }

    private Map<String, Object> jobEnvelope(CronJobEntity entity) {
        return jobEnvelope(formatJob(entity));
    }

    private Map<String, Object> jobEnvelope(Map<String, Object> job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("job", job);
        return result;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private Map<String, Object> formatJob(CronJobEntity job) {
        List<String> skills = splitCsv(job.getSkills());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", hermesJobId(job.getId()));
        result.put("id", hermesJobId(job.getId()));
        result.put("uuid", job.getId() == null ? null : job.getId().toString());
        result.put("name", job.getName());
        result.put("skill", skills.isEmpty() ? null : skills.get(0));
        result.put("skills", skills);
        result.put("prompt_preview", promptPreview(job.getPrompt()));
        result.put("model", blankToNull(job.getModelName()));
        result.put("provider", blankToNull(job.getModelProvider()));
        result.put("base_url", blankToNull(job.getBaseUrl()));
        result.put("schedule", job.getSchedule() == null || job.getSchedule().isBlank() ? "?" : job.getSchedule());
        result.put("repeat", repeatDisplay(job));
        result.put("deliver", job.getDeliverTo() == null || job.getDeliverTo().isBlank() ? "local" : job.getDeliverTo());
        result.put("next_run_at", instantString(job.getNextRunAt()));
        result.put("last_run_at", instantString(job.getLastRunAt()));
        result.put("last_status", blankToNull(job.getLastStatus()));
        result.put("last_error", blankToNull(job.getLastError()));
        result.put("enabled", job.isEnabled());
        result.put("state", job.isEnabled() ? "scheduled" : "paused");
        if (job.getRepeatCount() != null) {
            result.put("repeat_count", job.getRepeatCount());
            result.put("repeat_completed", job.getRepeatCompleted());
        }
        if (job.isNoAgent()) {
            result.put("no_agent", true);
        }
        if (job.getScript() != null && !job.getScript().isBlank()) {
            result.put("script", job.getScript());
        }
        if (job.getMonitor() != null && !job.getMonitor().isBlank()) {
            String monitor = job.getMonitor();
            result.put("monitor", monitor);
            if (isMonitorUrl(monitor)) {
                result.put("monitor_url", monitor);
            } else {
                result.put("monitor_script", monitor);
            }
        }
        if (job.getMonitorLastHash() != null && !job.getMonitorLastHash().isBlank()) {
            Map<String, Object> monitorState = new LinkedHashMap<>();
            monitorState.put("last_output_hash", job.getMonitorLastHash());
            monitorState.put("last_changed_at", instantString(job.getMonitorLastChangedAt()));
            monitorState.put("last_output", blankToNull(job.getMonitorLastOutput()));
            result.put("monitor_state", monitorState);
        }
        if (job.isContinuityEnabled()) {
            result.put("continuity", true);
        }
        if (job.getAttachedSessionId() != null) {
            result.put("attach_to_session", true);
            result.put("attached_session_id", job.getAttachedSessionId().toString());
        }
        List<String> enabledToolsets = splitCsv(job.getEnabledToolsets());
        if (!enabledToolsets.isEmpty()) {
            result.put("enabled_toolsets", enabledToolsets);
        }
        if (job.getWorkdir() != null && !job.getWorkdir().isBlank()) {
            result.put("workdir", job.getWorkdir());
        }
        List<String> contextFrom = splitCsv(job.getContextFrom());
        if (!contextFrom.isEmpty()) {
            result.put("context_from", contextFrom);
        }
        return result;
    }

    private static String hermesJobId(UUID id) {
        if (id == null) {
            return null;
        }
        return id.toString().replace("-", "").substring(0, 12);
    }

    private static List<String> canonicalSkills(String skill, Object skills) {
        List<String> normalized = new ArrayList<>();
        if (skills == null) {
            addSkill(normalized, skill);
        } else if (skills instanceof String string) {
            addSkill(normalized, string);
        } else if (skills instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !(item instanceof String)) {
                    throw new IllegalArgumentException("skills must be a string or array of strings");
                }
                addSkill(normalized, (String) item);
            }
        } else {
            throw new IllegalArgumentException("skills must be a string or array of strings");
        }
        return normalized;
    }

    private static void addSkill(List<String> normalized, String value) {
        if (value == null) {
            return;
        }
        String text = value.trim();
        if (!text.isEmpty() && !normalized.contains(text)) {
            normalized.add(text);
        }
    }

    private static String canonicalStringList(Object value, boolean emptyAsEmptyString) {
        if (value == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        if (value instanceof String string) {
            addCsvItems(normalized, string);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !(item instanceof String)) {
                    throw new IllegalArgumentException("expected a string or array of strings");
                }
                addCsvItems(normalized, (String) item);
            }
        } else {
            throw new IllegalArgumentException("expected a string or array of strings");
        }
        if (normalized.isEmpty()) {
            return emptyAsEmptyString ? "" : null;
        }
        return String.join(",", normalized);
    }

    private static void addCsvItems(List<String> normalized, String value) {
        if (value == null) {
            return;
        }
        for (String part : value.split(",")) {
            String text = part.trim();
            if (!text.isEmpty() && !normalized.contains(text)) {
                normalized.add(text);
            }
        }
    }

    private static String csvOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static String csvOrEmpty(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        addCsvItems(items, value);
        return items;
    }

    private static MonitorInput resolveMonitorInput(CronJobArgs args) {
        if (args.monitor() != null) {
            return new MonitorInput(true, args.monitor().trim());
        }
        String monitorScript = args.monitorScript();
        String monitorUrl = args.monitorUrl();
        boolean hasScript = monitorScript != null;
        boolean hasUrl = monitorUrl != null;
        if (!hasScript && !hasUrl) {
            return MonitorInput.absent();
        }
        String scriptValue = monitorScript == null ? null : monitorScript.trim();
        String urlValue = monitorUrl == null ? null : monitorUrl.trim();
        if (hasScript && hasUrl && !isBlank(scriptValue) && !isBlank(urlValue)) {
            throw new IllegalArgumentException("Use only one of monitor, monitor_script, or monitor_url");
        }
        if (hasUrl) {
            if (!isBlank(urlValue) && !isMonitorUrl(urlValue)) {
                throw new IllegalArgumentException("monitor_url must use http or https");
            }
            return new MonitorInput(true, urlValue == null ? "" : urlValue);
        }
        return new MonitorInput(true, scriptValue == null ? "" : scriptValue);
    }

    private static UUID attachedSessionId(Boolean attachToSession, Session session) {
        if (!Boolean.TRUE.equals(attachToSession)) {
            return null;
        }
        if (session == null || session.id() == null) {
            throw new IllegalArgumentException("attach_to_session=true requires a current session");
        }
        return session.id();
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

    private static String defaultJobName(String name, String prompt, List<String> skills) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (prompt != null && !prompt.isBlank()) {
            String trimmed = prompt.trim();
            return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
        }
        if (skills != null && !skills.isEmpty()) {
            return skills.get(0);
        }
        return "cron job";
    }

    private static String promptPreview(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;
    }

    private static String repeatDisplay(CronJobEntity job) {
        Integer times = job.getRepeatCount();
        int completed = job.getRepeatCompleted();
        if (times == null) {
            return "forever";
        }
        if (times == 1) {
            return completed == 0 ? "once" : "1/1";
        }
        return completed == 0 ? times + " times" : completed + "/" + times;
    }

    private static String instantString(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record MonitorInput(boolean provided, String value) {
        static MonitorInput absent() {
            return new MonitorInput(false, null);
        }
    }

    record CronJobArgs(
        @ToolParam(description = "One of: create, list, update, pause, resume, remove, run.", required = true) String action,
        @ToolParam(description = "Required for update/pause/resume/remove/run", required = false) @JsonProperty("job_id") @JsonAlias("jobId") String jobId,
        @ToolParam(description = "Human-friendly job name.", required = false) String name,
        @ToolParam(description = "Schedule: '30m', 'every 2h', '0 9 * * *', or ISO timestamp.", required = false) String schedule,
        @ToolParam(description = "Self-contained prompt for the agent to execute each tick.", required = false) String prompt,
        @JsonProperty("deliver") @JsonAlias({"deliver_to", "deliverTo"}) String deliver,
        @ToolParam(description = "Legacy single skill name.", required = false) String skill,
        @ToolParam(description = "Ordered skill name or array of skill names to load before executing the prompt.", required = false) Object skills,
        @JsonProperty("context_from") Object contextFrom,
        @ToolParam(description = "Repeat count (omit for defaults).", required = false) Integer repeat,
        @ToolParam(description = "Script path that runs each tick (relative to ~/.hermes/scripts/).", required = false) String script,
        String monitor,
        @JsonProperty("monitor_script") String monitorScript,
        @JsonProperty("monitor_url") String monitorUrl,
        @JsonProperty("no_agent") Boolean noAgent,
        @JsonProperty("continuity") Boolean continuity,
        @JsonProperty("enabled_toolsets") Object enabledToolsets,
        @ToolParam(description = "Absolute working directory for the job.", required = false) String workdir,
        @JsonProperty("attach_to_session") Boolean attachToSession,
        @JsonProperty("model_provider") String modelProvider,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("base_url") String baseUrl
    ) {}
}
