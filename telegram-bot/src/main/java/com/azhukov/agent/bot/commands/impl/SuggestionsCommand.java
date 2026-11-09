package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * Hermes parity (hermes_cli/suggestions_cmd.py): review suggested
 * automations — the curated catalog store with a latched dismiss,
 * NOT a CRUD shim over live cron jobs.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>(bare) — list pending suggestions</li>
 *   <li>catalog — seed the curated starter automations as pending</li>
 *   <li>accept id — create the real cron job from the suggestion</li>
 *   <li>dismiss id — dismiss it (latched: never re-offered)</li>
 *   <li>clear — drop accepted records (housekeeping)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SuggestionsCommand implements CommandHandler {

    private final com.azhukov.agent.bot.core.AgentBackendClient backendClient;

    @Override
    public String name() {
        return "suggestions";
    }

    @Override
    public String description() {
        return "Review suggested automations (accept/dismiss)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String ownerId = Long.toString(event.userId());
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listSuggestions(ownerId);
        }

        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String ref = parts.length > 1 ? parts[1].trim() : "";

        return switch (sub) {
            case "catalog" -> {
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/catalog", ownerId);
                int added = resp == null ? -1 : resp.path("added").asInt(-1);
                yield added < 0
                    ? "Failed to seed the catalog."
                    : (added == 0
                        ? "Catalog already seeded — nothing new to add. Send /suggestions to review."
                        : "Seeded " + added + " starter suggestions. Send /suggestions to review them.");
            }
            case "accept" -> {
                if (ref.isEmpty()) yield "Usage: /suggestions accept <id>";
                UUID id = parseId(ref);
                if (id == null) yield "Use the suggestion ID shown by /suggestions.";
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + id + "/accept", ownerId);
                if (resp == null) yield "Could not confirm acceptance. Check /suggestions and /cron.";
                if (!resp.path("accepted").asBoolean(false)) {
                    yield "Could not accept (not found or already decided): " + ref;
                }
                String scheduling = resp.path("schedulingStatus").asText("");
                if ("failed".equals(scheduling)) {
                    yield "Created cron job: " + resp.path("name").asText("?") + ", but scheduling failed; check the cron job status.";
                }
                if ("not_scheduled".equals(scheduling) || "unknown".equals(scheduling)) {
                    yield "Created cron job: " + resp.path("name").asText("?") + ", but scheduling is not confirmed; check the cron job status.";
                }
                yield "✅ Created cron job: " + resp.path("name").asText("?");
            }
            case "dismiss" -> {
                if (ref.isEmpty()) yield "Usage: /suggestions dismiss <id>";
                UUID id = parseId(ref);
                if (id == null) yield "Use the suggestion ID shown by /suggestions.";
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + id + "/dismiss", ownerId);
                yield resp != null && resp.path("dismissed").asBoolean(false)
                    ? "Dismissed — it won't be suggested again."
                    : "Could not dismiss: " + ref;
            }
            case "clear" -> {
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/clear", ownerId);
                yield resp == null ? "Could not confirm cleanup." : "Cleared " + resp.path("cleared").asInt(0) + " accepted records.";
            }
            case "list" -> listSuggestions(ownerId);
            default -> "Unknown subcommand: " + sub + "\n"
                + "Usage: /suggestions [catalog | accept <id> | dismiss <id> | clear]";
        };
    }

    private static UUID parseId(String ref) {
        try {
            UUID id = UUID.fromString(ref);
            return id.toString().equalsIgnoreCase(ref) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String listSuggestions(String ownerId) {
        JsonNode pending = backendClient.listSuggestions(ownerId);
        if (pending == null || !pending.isArray()) return "Could not load suggested automations. Try again.";
        if (pending.isEmpty()) {
            return "No suggested automations.\n"
                + "Try `/suggestions catalog` to see the curated starter set, or `/cron` to create one.";
        }
        StringBuilder sb = new StringBuilder("Suggested automations:\n\n");
        int n = 1;
        for (JsonNode s : pending) {
            String title = s.path("title").asText("unnamed");
            String desc = s.path("description").asText("");
            sb.append(n).append(". **").append(title).append("**\n")
              .append("   ").append(desc).append("\n")
              .append("   ID: ").append((char) 96).append(s.path("id").asText()).append((char) 96).append("\n");
            n++;
        }
        sb.append("\nCommands: /suggestions accept <id> | dismiss <id> | catalog | clear");
        return sb.toString().trim();
    }
}
