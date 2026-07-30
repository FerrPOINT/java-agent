package com.azhukov.agent.tools.cron;

import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@AgentTool(
    name = "cronjob",
    description = "Create and manage scheduled cron jobs for the agent. Actions: create, list, pause, resume, remove, run.",
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
            case "create" -> createJob(args);
            case "list" -> listJobs();
            case "pause" -> pauseJob(args);
            case "resume" -> resumeJob(args);
            case "remove" -> removeJob(args);
            case "run" -> runJob(args);
            default -> ToolResult.fail("Unknown cron action: " + action + ". Use: create, list, pause, resume, remove, run");
        };
    }

    private ToolResult createJob(CronJobArgs args) {
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        if (args.schedule() == null || args.schedule().isBlank()) return ToolResult.fail("schedule is required");
        if (args.prompt() == null || args.prompt().isBlank()) return ToolResult.fail("prompt is required");
        try {
            CronJobEntity entity = cronJobService.create(args.name(), args.schedule(), args.prompt(), args.deliverTo(), args.skills());
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
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        try {
            Optional<CronJobEntity> job = cronJobService.findByName(args.name());
            if (job.isEmpty()) return ToolResult.fail("Cron job not found: " + args.name());
            CronJobEntity entity = cronJobService.pause(job.get().getId());
            return ToolResult.ok("Paused cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to pause cron job: " + e.getMessage());
        }
    }

    private ToolResult resumeJob(CronJobArgs args) {
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        try {
            Optional<CronJobEntity> job = cronJobService.findByName(args.name());
            if (job.isEmpty()) return ToolResult.fail("Cron job not found: " + args.name());
            CronJobEntity entity = cronJobService.resume(job.get().getId());
            return ToolResult.ok("Resumed cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to resume cron job: " + e.getMessage());
        }
    }

    private ToolResult removeJob(CronJobArgs args) {
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        try {
            Optional<CronJobEntity> job = cronJobService.findByName(args.name());
            if (job.isEmpty()) return ToolResult.fail("Cron job not found: " + args.name());
            cronJobService.remove(job.get().getId());
            return ToolResult.ok("Removed cron job: " + args.name());
        } catch (Exception e) {
            return ToolResult.fail("Failed to remove cron job: " + e.getMessage());
        }
    }

    private ToolResult runJob(CronJobArgs args) {
        if (args.name() == null || args.name().isBlank()) return ToolResult.fail("name is required");
        try {
            Optional<CronJobEntity> job = cronJobService.findByName(args.name());
            if (job.isEmpty()) return ToolResult.fail("Cron job not found: " + args.name());
            CronJobEntity entity = cronJobService.runNow(job.get().getId());
            return ToolResult.ok("Triggered cron job: " + entity.getName());
        } catch (Exception e) {
            return ToolResult.fail("Failed to run cron job: " + e.getMessage());
        }
    }

    private String formatJob(CronJobEntity job) {
        return String.format("id=%s | name=%s | schedule=%s | enabled=%s | lastRun=%s | nextRun=%s",
            job.getId(), job.getName(), job.getSchedule(), job.isEnabled(),
            job.getLastRunAt(), job.getNextRunAt());
    }

    record CronJobArgs(
        String action,
        String name,
        String schedule,
        String prompt,
        @JsonProperty("deliver_to") String deliverTo,
        String skills
    ) {}
}