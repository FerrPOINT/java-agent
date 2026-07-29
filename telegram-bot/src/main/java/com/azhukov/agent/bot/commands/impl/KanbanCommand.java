package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * /kanban — Show active agents and background tasks as a simple board.
 * /kanban           — list active agents/tasks
 * /kanban list      — same as above
 *
 * Note: Full multi-profile kanban board is not available in this build.
 * This shows active background agents as a lightweight task board.
 */
@Component
public class KanbanCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public KanbanCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "kanban";
    }

    @Override
    public String description() {
        return "Show active agents and tasks";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args != null && !args.isBlank()) {
            String sub = args.trim().toLowerCase();
            if (!sub.equals("list")) {
                return "Subcommands not supported in this build.\nUsage: /kanban — show active agents";
            }
        }

        JsonNode agents = backendClient.listActiveAgents();
        if (agents == null || !agents.isArray() || agents.isEmpty()) {
            return "No active agents or tasks.\n\nFull kanban board is not available in this build.";
        }

        StringBuilder sb = new StringBuilder("Active agents & tasks:\n");
        int maxShow = Math.min(agents.size(), 15);
        for (int i = 0; i < maxShow; i++) {
            JsonNode agent = agents.get(i);
            String id = agent.path("id").asText("?");
            String status = agent.path("status").asText("unknown");
            String prompt = agent.path("prompt").asText("");
            if (prompt.length() > 60) prompt = prompt.substring(0, 57) + "...";

            sb.append("  [").append(id, 0, Math.min(id.length(), 8)).append("] ")
              .append(status).append(" — ").append(prompt).append("\n");
        }
        if (agents.size() > maxShow) {
            sb.append("  ... and ").append(agents.size() - maxShow).append(" more\n");
        }

        sb.append("\nFull kanban board (create, assign, comment) is not available in this build.");
        return sb.toString().trim();
    }
}