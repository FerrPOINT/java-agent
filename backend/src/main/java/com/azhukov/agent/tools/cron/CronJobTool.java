package com.azhukov.agent.tools.cron;

import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@AgentTool(
    name = "cronjob",
    description = "Manage scheduled cron jobs with a single compressed tool.\n\nUse action='create' to schedule a new job from a prompt or one or more skills.\nUse action='list' to inspect jobs.\nUse action='update', 'pause', 'resume', 'remove', or 'run' to manage an existing job.\n\naction='run' fires the job immediately in the BACKGROUND (like delegate_task): the call returns at once with a handle and the job's outcome re-enters the conversation as a new message when it finishes. Do not wait or poll after triggering a run — just continue. Optionally pass 'prompt' with action='run' to inject transient per-run context (appended to the job's stored prompt for that single fire only, never persisted).\n\nTo stop a job the user no longer wants: first action='list' to find the job_id, then action='remove' with that job_id. Never guess job IDs — always list first.\n\nJobs run in a fresh session with no current-chat context, so prompts must be self-contained.\nIf skills are provided on create, the future cron run loads those skills in order, then follows the prompt as the task instruction.\nOn update, passing skills=[] clears attached skills.\n\nNOTE: The agent's final response is auto-delivered to the target. Put the primary\nuser-facing content in the final response. Cron jobs run autonomously with no user\npresent — they cannot ask questions or request clarification.\n\nScheduling from cron-run sessions is disabled by default and enabled via cron.allow_agent_scheduling in config.yaml. When enabled, jobs created from a cron run are user-owned in the same flat job table as every other job, and their delivery resolves to the creating job's own persistent target — never to the ephemeral cron-run session. Prefer updating an existing job (list first, then update by job_id) over creating near-duplicates.",
    toolset = "core"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class CronJobTool implements ToolHandler {

    private final CronJobService cronJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        CronJobArgs args = ToolHandler.parseJson(arguments, CronJobArgs.class);
        String action = args.action() == null ? "" : args.action().toLowerCase();

        return switch (action) {
            case "create" -> createJob(args, session);
            case "list" -> listJobs();
            case "pause" -> pauseJob(args);
            case "resume" -> resumeJob(args);
            case "remove" -> removeJob(args);
            case "run" -> runJob(args);
            case "update" -> updateJob(args);
            default -> ToolResult.fail("Unknown cron action: " + action + ". Use: create, list, pause, resume, remove, run, update");
        };
    }

    private ToolResult createJob(CronJobArgs args, Session session) {
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        if (args.schedule() == null || args.schedule().isBlank()) return ToolResult.fail("schedule is required");
        // prompt is optional when no_agent=true (script is the job)
        if (!Boolean.TRUE.equals(args.noAgent()) && (args.prompt() == null || args.prompt().isBlank())) {
            return ToolResult.fail("prompt is required (or set no_agent=true with script)");
        }
        try {
            // rev-89: pass the session's userId for job ownership — required for
            // multi-user cron scoping (list/update/delete check requireOwnership).
            String userId = session != null ? session.userId() : null;
            CronJobEntity entity = cronJobService.create(
                userId,
                args.name(), args.schedule(), args.prompt(), args.deliver(),
                args.skills(), args.contextFrom(),
                args.repeat(),
                args.script(), args.noAgent() != null && args.noAgent(),
                args.enabledToolsets(), args.workdir(),
                args.modelProvider(), args.modelName(), args.baseUrl()
            );
            return ToolResult.ok(formatJob(entity));
        } catch (Exception e) {
            return ToolResult.fail("Failed to create cron job: " + e.getMessage());
        }
    }

    private ToolResult listJobs() {
        try {
            List<CronJobEntity> jobs = cronJobService.list();
            StringBuilder sb = new StringBuilder();
            sb.append("Cron jobs (").append(jobs.size()).append("):\n");
            for (CronJobEntity job : jobs) {
                sb.append(formatJob(job)).append("\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail("Failed to list cron jobs: " + e.getMessage());
        }
    }

    private ToolResult pauseJob(CronJobArgs args) {
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return ToolResult.fail(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.pause(job.get().getId());
            return ToolResult.ok("Paused cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to pause cron job: " + e.getMessage());
        }
    }

    private ToolResult resumeJob(CronJobArgs args) {
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return ToolResult.fail(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.resume(job.get().getId());
            return ToolResult.ok("Resumed cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to resume cron job: " + e.getMessage());
        }
    }

    private ToolResult removeJob(CronJobArgs args) {
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return ToolResult.fail(jobNotFoundMsg(args));
        try {
            cronJobService.remove(job.get().getId());
            return ToolResult.ok("Removed cron job: " + job.get().getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to remove cron job: " + e.getMessage());
        }
    }

    private ToolResult runJob(CronJobArgs args) {
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return ToolResult.fail(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.runNow(job.get().getId());
            return ToolResult.ok("Triggered cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to run cron job: " + e.getMessage());
        }
    }

    private ToolResult updateJob(CronJobArgs args) {
        Optional<CronJobEntity> job = resolveJob(args);
        if (job.isEmpty()) return ToolResult.fail(jobNotFoundMsg(args));
        try {
            CronJobEntity entity = cronJobService.update(
                job.get().getId(), args.name(), args.schedule(), args.prompt(), args.deliver(), null,
                args.skills(), args.contextFrom(),
                args.repeat(),
                args.script(), args.noAgent(),
                args.enabledToolsets(), args.workdir(),
                args.modelProvider(), args.modelName(), args.baseUrl()
            );
            return ToolResult.ok("Updated cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to update cron job: " + e.getMessage());
        }
    }

    /**
     * Hermes parity: resolve a job by job_id first (as the tool description says),
     * falling back to name if job_id is absent.
     */
    private Optional<CronJobEntity> resolveJob(CronJobArgs args) {
        if (args.jobId() != null && !args.jobId().isBlank()) {
            try {
                return cronJobService.findById(java.util.UUID.fromString(args.jobId()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        if (args.name() != null && !args.name().isBlank()) {
            return cronJobService.findByName(args.name());
        }
        return Optional.empty();
    }

    private static String jobNotFoundMsg(CronJobArgs args) {
        if (args.jobId() != null && !args.jobId().isBlank()) {
            return "Cron job not found: job_id=" + args.jobId();
        }
        return "Cron job not found: " + args.name();
    }

    private String formatJob(CronJobEntity job) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("id=%s | name=%s | schedule=%s | enabled=%s | lastRun=%s | nextRun=%s",
            job.getId(), job.getName(), job.getSchedule(), job.isEnabled(),
            job.getLastRunAt(), job.getNextRunAt()));
        if (job.getRepeatCount() != null) {
            sb.append(String.format(" | repeat=%d/%d", job.getRepeatCompleted(), job.getRepeatCount()));
        }
        if (job.isNoAgent()) {
            sb.append(" | noAgent=true");
        }
        if (job.getScript() != null && !job.getScript().isBlank()) {
            sb.append(" | script=").append(job.getScript());
        }
        if (job.getEnabledToolsets() != null && !job.getEnabledToolsets().isBlank()) {
            sb.append(" | toolsets=").append(job.getEnabledToolsets());
        }
        if (job.getWorkdir() != null && !job.getWorkdir().isBlank()) {
            sb.append(" | workdir=").append(job.getWorkdir());
        }
        if (job.getModelProvider() != null && !job.getModelProvider().isBlank()) {
            sb.append(" | provider=").append(job.getModelProvider());
        }
        if (job.getModelName() != null && !job.getModelName().isBlank()) {
            sb.append(" | model=").append(job.getModelName());
        }
        if (job.getBaseUrl() != null && !job.getBaseUrl().isBlank()) {
            sb.append(" | baseUrl=").append(job.getBaseUrl());
        }
        return sb.toString();
    }

    record CronJobArgs(
        @ToolParam(description = "One of: create, list, update, pause, resume, remove, run.", required = true) String action,
        @ToolParam(description = "Required for update/pause/resume/remove/run", required = false) @JsonProperty("job_id") @JsonAlias("jobId") String jobId,
        @ToolParam(description = "Human-friendly job name.", required = false) String name,
        @ToolParam(description = "Schedule: '30m', 'every 2h', '0 9 * * *', or ISO timestamp.", required = false) String schedule,
        @ToolParam(description = "Self-contained prompt for the agent to execute each tick.", required = false) String prompt,
        @JsonProperty("deliver") @JsonAlias({"deliver_to", "deliverTo"}) String deliver,
        @ToolParam(description = "Ordered list of skill names to load before executing the prompt.", required = false) String skills,
        @JsonProperty("context_from") String contextFrom,
        @ToolParam(description = "Repeat count (omit for defaults).", required = false) Integer repeat,
        @ToolParam(description = "Script path that runs each tick (relative to ~/.hermes/scripts/).", required = false) String script,
        @JsonProperty("no_agent") Boolean noAgent,
        @JsonProperty("enabled_toolsets") String enabledToolsets,
        @ToolParam(description = "Absolute working directory for the job.", required = false) String workdir,
        @JsonProperty("model_provider") String modelProvider,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("base_url") String baseUrl
    ) {}
}