package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * /curator — Background skill maintenance: status, run, pin, archive.
 * /curator         — show skill curator status
 * /curator run     — trigger skill review/refresh
 * /curator reload  — reload skills from disk
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CuratorCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "curator";
    }

    @Override
    public String description() {
        return "Skill maintenance: status, run, reload";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return showStatus();
        }

        String sub = args.trim().toLowerCase();
        return switch (sub) {
            case "status" -> showStatus();
            case "run", "review" -> {
                String result = backendClient.reloadSkills();
                yield result != null ? result : "Skill review triggered.";
            }
            case "reload" -> {
                String result = backendClient.reloadSkills();
                yield result != null ? result : "Skills reloaded.";
            }
            default -> "Unknown subcommand: " + sub + "\n"
                + "Usage: /curator [status|run|reload]";
        };
    }

    private String showStatus() {
        JsonNode skills = backendClient.getSkills();
        if (skills == null || skills.isMissingNode() || skills.isNull()) {
            return "Skills not available. Backend may be offline.";
        }

        int count = skills.isArray() ? skills.size() : 0;
        StringBuilder sb = new StringBuilder("Skill curator status:\n");
        sb.append("  Total skills: ").append(count).append("\n");

        if (skills.isArray() && count > 0) {
            int maxShow = Math.min(count, 10);
            sb.append("  Skills (").append(maxShow).append(" of ").append(count).append("):\n");
            for (int i = 0; i < maxShow; i++) {
                JsonNode skill = skills.get(i);
                String name = skill.path("name").asText("unknown");
                String desc = skill.path("description").asText("");
                if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
                sb.append("    ").append(name);
                if (!desc.isEmpty()) sb.append(" — ").append(desc);
                sb.append("\n");
            }
            if (count > maxShow) {
                sb.append("    ... and ").append(count - maxShow).append(" more\n");
            }
        }

        sb.append("\nCommands: /curator run — review & reload skills");
        return sb.toString().trim();
    }
}