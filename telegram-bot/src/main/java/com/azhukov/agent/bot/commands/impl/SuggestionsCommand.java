package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * /suggestions — Review and manage suggested automations (cron jobs).
 * /suggestions              — list pending cron jobs
 * /suggestions accept <id>  — resume (enable) a paused cron job
 * /suggestions dismiss <id> — delete a cron job
 * /suggestions clear        — delete all cron jobs
 */
@Component
public class SuggestionsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public SuggestionsCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "suggestions";
    }

    @Override
    public String description() {
        return "Review suggested automations";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listSuggestions();
        }

        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();

        return switch (sub) {
            case "accept" -> {
                if (parts.length < 2) yield "Usage: /suggestions accept <id>";
                String id = parts[1].trim();
                boolean ok = backendClient.resumeCronJob(id);
                yield ok ? "Suggestion " + id + " accepted (enabled)." : "Failed to accept suggestion " + id;
            }
            case "dismiss" -> {
                if (parts.length < 2) yield "Usage: /suggestions dismiss <id>";
                String id = parts[1].trim();
                boolean ok = backendClient.deleteCronJob(id);
                yield ok ? "Suggestion " + id + " dismissed." : "Failed to dismiss suggestion " + id;
            }
            case "clear" -> {
                JsonNode jobs = backendClient.listCronJobs();
                int count = 0;
                if (jobs.isArray()) {
                    for (JsonNode job : jobs) {
                        String id = job.path("id").asText("");
                        if (!id.isEmpty() && backendClient.deleteCronJob(id)) count++;
                    }
                }
                yield "Cleared " + count + " suggestions.";
            }
            case "list" -> listSuggestions();
            default -> "Unknown subcommand: " + sub + "\n"
                + "Usage: /suggestions [list|accept <id>|dismiss <id>|clear]";
        };
    }

    private String listSuggestions() {
        JsonNode jobs = backendClient.listCronJobs();
        if (jobs == null || !jobs.isArray() || jobs.isEmpty()) {
            return "No suggested automations. Use /cron to create one.";
        }

        StringBuilder sb = new StringBuilder("Suggested automations:\n");
        int maxShow = Math.min(jobs.size(), 15);
        for (int i = 0; i < maxShow; i++) {
            JsonNode job = jobs.get(i);
            String id = job.path("id").asText("?");
            String name = job.path("name").asText("unnamed");
            String schedule = job.path("schedule").asText("?");
            boolean enabled = job.path("enabled").asBoolean(false);
            sb.append("  [").append(id, 0, Math.min(id.length(), 8)).append("] ")
              .append(name).append(" — ").append(schedule)
              .append(enabled ? " (active)" : " (paused)")
              .append("\n");
        }
        if (jobs.size() > maxShow) {
            sb.append("  ... and ").append(jobs.size() - maxShow).append(" more\n");
        }
        sb.append("\nCommands: /suggestions accept <id> | /suggestions dismiss <id> | /suggestions clear");
        return sb.toString().trim();
    }
}