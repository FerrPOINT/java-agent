package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/**
 * Hermes parity (hermes_cli/suggestions_cmd.py): review suggested
 * automations — the curated catalog store with a latched dismiss,
 * NOT a CRUD shim over live cron jobs.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>(bare) — list pending suggestions (numbered)</li>
 *   <li>catalog — seed the curated starter automations as pending</li>
 *   <li>accept N|id — create the real cron job from the suggestion</li>
 *   <li>dismiss N|id — dismiss it (latched: never re-offered)</li>
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
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listSuggestions();
        }

        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String ref = parts.length > 1 ? parts[1].trim() : "";

        return switch (sub) {
            case "catalog" -> {
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/catalog");
                int added = resp == null ? -1 : resp.path("added").asInt(-1);
                yield added < 0
                    ? "Failed to seed the catalog."
                    : (added == 0
                        ? "Catalog already seeded — nothing new to add. Send /suggestions to review."
                        : "Seeded " + added + " starter suggestions. Send /suggestions to review them.");
            }
            case "accept" -> {
                if (ref.isEmpty()) yield "Usage: /suggestions accept <N|id>";
                String id = resolveRef(ref);
                if (id == null) yield "No such suggestion: " + ref;
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + id + "/accept");
                yield resp != null && resp.path("accepted").asBoolean(false)
                    ? "✅ Created cron job: " + resp.path("name").asText("?")
                    : "Could not accept (not found or already decided): " + ref;
            }
            case "dismiss" -> {
                if (ref.isEmpty()) yield "Usage: /suggestions dismiss <N|id>";
                String id = resolveRef(ref);
                if (id == null) yield "No such suggestion: " + ref;
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + id + "/dismiss");
                yield resp != null && resp.path("dismissed").asBoolean(false)
                    ? "Dismissed — it won't be suggested again."
                    : "Could not dismiss: " + ref;
            }
            case "clear" -> {
                JsonNode resp = backendClient.suggestionPost("/api/v1/agent/cron/suggestions/clear");
                yield "Cleared " + (resp == null ? 0 : resp.path("cleared").asInt(0)) + " accepted records.";
            }
            case "list" -> listSuggestions();
            default -> "Unknown subcommand: " + sub + "\n"
                + "Usage: /suggestions [catalog | accept <N> | dismiss <N> | clear]";
        };
    }

    /** Resolve a numbered reference ("2") or raw id to the suggestion id. */
    private String resolveRef(String ref) {
        JsonNode pending = backendClient.listSuggestions();
        if (pending == null || !pending.isArray()) return null;
        int n = 1;
        for (JsonNode s : pending) {
            String id = s.path("id").asText("");
            if (ref.equals(id)) return id;
            if (ref.equals(String.valueOf(n))) return id;
            n++;
        }
        return null;
    }

    private String listSuggestions() {
        JsonNode pending = backendClient.listSuggestions();
        if (pending == null || !pending.isArray() || pending.isEmpty()) {
            return "No suggested automations.\n"
                + "Try `/suggestions catalog` to see the curated starter set, or `/cron` to create one.";
        }
        StringBuilder sb = new StringBuilder("Suggested automations — `/suggestions accept N` or `dismiss N`:\n\n");
        int n = 1;
        for (JsonNode s : pending) {
            String title = s.path("title").asText("unnamed");
            String desc = s.path("description").asText("");
            sb.append(n).append(". **").append(title).append("**\n")
              .append("   ").append(desc).append("\n");
            n++;
        }
        sb.append("\nCommands: /suggestions accept <N> | dismiss <N> | catalog | clear");
        return sb.toString().trim();
    }
}
