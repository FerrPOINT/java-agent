package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * /suggestions — the cron suggestion catalog surface in the CLI.
 *
 * <p>Hermes parity: suggestions are a catalog of curated recurring-task
 * ideas plus a per-user pending store with a latched dismiss. The CLI
 * mirrors the bot command: list pending, show details, accept (creates the
 * cron job), dismiss (latched forever), and re-seed the catalog.
 */
@Component
public final class SuggestionCommands implements CommandGroup {

    private static final Logger log = LoggerFactory.getLogger(SuggestionCommands.class);

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("suggestions", "Cron suggestions: /suggestions [list|accept <id>|dismiss <id>|seed]", (args, client, sessionId) -> {
            String trimmed = args == null ? "" : args.trim();
            String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
            String sub = parts.length > 0 ? parts[0] : "list";
            switch (sub) {
                case "list" -> { return listPending(client); }
                case "accept" -> {
                    if (parts.length < 2) return "Usage: /suggestions accept <id>";
                    return accept(client, parts[1]);
                }
                case "dismiss" -> {
                    if (parts.length < 2) return "Usage: /suggestions dismiss <id>";
                    return dismiss(client, parts[1]);
                }
                case "seed", "reseed" -> { return seed(client); }
                default -> {
                    return "Usage: /suggestions [list|accept <id>|dismiss <id>|seed]";
                }
            }
        });
    }

    private String listPending(BackendClient client) {
        try {
            JsonNode pending = client.executeGet("/api/v1/agent/cron/suggestions");
            if (pending == null || !pending.isArray() || pending.isEmpty()) {
                return "No pending suggestions.\nUse /suggestions seed to load the catalog.";
            }
            StringBuilder sb = new StringBuilder("Pending cron suggestions:\n");
            for (JsonNode s : pending) {
                sb.append("\n• ").append(s.path("title").asText());
                sb.append("\n  id: ").append(s.path("id").asText());
                String desc = s.path("description").asText("");
                if (!desc.isBlank()) {
                    sb.append("\n  ").append(desc);
                }
                String sched = s.path("schedule").asText("");
                if (!sched.isBlank()) {
                    sb.append("\n  schedule: ").append(sched);
                }
            }
            sb.append("\n\n/suggestions accept <id> — create the cron job");
            sb.append("\n/suggestions dismiss <id> — dismiss forever");
            return sb.toString();
        } catch (Exception e) {
            log.warn("suggestions list failed: {}", e.getMessage());
            return "Failed to load suggestions: " + e.getMessage();
        }
    }

    private String accept(BackendClient client, String id) {
        try {
            JsonNode res = client.executePost("/api/v1/agent/cron/suggestions/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/accept", Map.of());
            if (res != null && res.path("accepted").asBoolean(false)) {
                String name = res.path("jobName").asText(res.path("name").asText(""));
                return "Suggestion accepted" + (name.isBlank() ? "." : ": cron job '" + name + "' created.");
            }
            String reason = res == null ? "no response" : res.path("reason").asText("unknown id");
            return "Suggestion not accepted: " + reason;
        } catch (Exception e) {
            return "Accept failed: " + e.getMessage();
        }
    }

    private String dismiss(BackendClient client, String id) {
        try {
            JsonNode res = client.executePost("/api/v1/agent/cron/suggestions/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/dismiss", Map.of());
            if (res != null && res.path("dismissed").asBoolean(false)) {
                return "Suggestion dismissed (will not be suggested again).";
            }
            return "Dismiss failed: " + (res == null ? "no response" : res.path("reason").asText("unknown id"));
        } catch (Exception e) {
            return "Dismiss failed: " + e.getMessage();
        }
    }

    private String seed(BackendClient client) {
        try {
            JsonNode res = client.executePost("/api/v1/agent/cron/suggestions/catalog", Map.of());
            int added = res == null ? -1 : res.path("added").asInt(-1);
            if (added < 0) return "Seed failed: no response";
            return added == 0
                ? "Catalog already seeded (0 new)."
                : "Seeded " + added + " new suggestion(s).";
        } catch (Exception e) {
            return "Seed failed: " + e.getMessage();
        }
    }
}
