package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * /kanban — Full kanban board backed by the backend todos table.
 * /kanban           — list all tasks
 * /kanban list      — same as above
 * /kanban add <text> — add a new task
 * /kanban done <id> — mark a task as done
 * /kanban clear     — remove all tasks
 */
@Component
@RequiredArgsConstructor
public class KanbanCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() {
        return "kanban";
    }

    @Override
    public String description() {
        return "Kanban board: list, add, done, clear";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank() || args.trim().equalsIgnoreCase("list")) {
            return listBoard();
        }

        String[] parts = args.trim().split("\\s+", 2);
        String subcommand = parts[0].toLowerCase();

        return switch (subcommand) {
            case "add" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    yield "Usage: /kanban add <text>";
                }
                yield addTask(parts[1].trim());
            }
            case "done" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    yield "Usage: /kanban done <id>";
                }
                yield doneTask(parts[1].trim());
            }
            case "clear" -> clearBoard();
            default -> "Unknown subcommand: " + subcommand + "\nUsage: /kanban [list|add <text>|done <id>|clear]";
        };
    }

    private String listBoard() {
        JsonNode board = backendClient.getKanban();
        if (board == null || !board.isArray() || board.isEmpty()) {
            return "📋 Kanban board is empty.\n\nUsage: /kanban add <text>";
        }

        StringBuilder sb = new StringBuilder("📋 Kanban board:\n\n");
        boolean hasPending = false;
        boolean hasDone = false;

        StringBuilder pending = new StringBuilder();
        StringBuilder done = new StringBuilder();

        for (JsonNode task : board) {
            String id = task.path("id").asText("?");
            String title = task.path("title").asText("(no title)");
            String status = task.path("status").asText("pending");
            String shortId = id.length() > 8 ? id.substring(0, 8) : id;

            if ("done".equalsIgnoreCase(status)) {
                done.append("  ✅ [").append(shortId).append("] ").append(title).append("\n");
                hasDone = true;
            } else {
                pending.append("  📌 [").append(shortId).append("] ").append(title).append("\n");
                hasPending = true;
            }
        }

        if (hasPending) {
            sb.append("Pending:\n").append(pending);
        }
        if (hasDone) {
            sb.append("Done:\n").append(done);
        }
        if (!hasPending && !hasDone) {
            return "📋 Kanban board is empty.\n\nUsage: /kanban add <text>";
        }

        sb.append("\n/kanban add <text> — add task\n");
        sb.append("/kanban done <id> — mark done\n");
        sb.append("/kanban clear — remove all");
        return sb.toString().trim();
    }

    private String addTask(String text) {
        JsonNode result = backendClient.addKanbanTask(text);
        if (result == null) {
            return "❌ Failed to add task.";
        }
        String id = result.path("id").asText("?");
        String shortId = id.length() > 8 ? id.substring(0, 8) : id;
        return "✅ Added task: [" + shortId + "] " + text;
    }

    private String doneTask(String id) {
        boolean success = backendClient.doneKanbanTask(id);
        return success
            ? "✅ Task marked as done: " + id
            : "❌ Failed to mark task as done (not found or invalid id)";
    }

    private String clearBoard() {
        boolean success = backendClient.clearKanban();
        return success
            ? "🧹 Kanban board cleared."
            : "❌ Failed to clear kanban board.";
    }
}