package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class MemoryCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public MemoryCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Manage memory: list, pending, approve, reject, approval, add, remove";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listAllMemory();
        }

        String[] parts = args.split("\\s+", 2);
        String subcommand = parts[0].toLowerCase();

        return switch (subcommand) {
            case "pending" -> listPending();
            case "approve" -> {
                if (parts.length < 2) yield "Usage: /memory approve <id>";
                yield approvePending(parts[1].trim());
            }
            case "reject" -> {
                if (parts.length < 2) yield "Usage: /memory reject <id>";
                yield rejectPending(parts[1].trim());
            }
            case "approval" -> {
                if (parts.length < 2) yield "Usage: /memory approval on|off";
                yield toggleApproval(parts[1].trim());
            }
            case "add" -> {
                if (parts.length < 2) yield "Usage: /memory add <text>";
                yield addMemory(parts[1].trim());
            }
            case "remove" -> {
                if (parts.length < 2) yield "Usage: /memory remove <text>";
                yield removeMemory(parts[1].trim());
            }
            default -> "Unknown subcommand: " + subcommand + "\nUsage: /memory [pending|approve|reject|approval|add|remove]";
        };
    }

    private String listAllMemory() {
        JsonNode node = backendClient.listAllMemory("default");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "No memory facts stored.";
        }
        StringBuilder sb = new StringBuilder("Memory facts:\n");
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String target = entry.path("target").asText("memory");
            String fact = entry.path("fact").asText("");
            String category = entry.path("category").asText("");
            String id = entry.path("id").asText("");
            sb.append("  [").append(target).append("] ");
            if (!category.isEmpty()) sb.append("(").append(category).append(") ");
            sb.append(fact);
            sb.append("  (id: ").append(id, 0, Math.min(8, id.length())).append(")\n");
        }
        return sb.toString().trim();
    }

    private String listPending() {
        JsonNode node = backendClient.listPendingMemory("default");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "No pending memory writes.";
        }
        StringBuilder sb = new StringBuilder("Pending memory writes:\n");
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String action = entry.path("action").asText("");
            String target = entry.path("target").asText("memory");
            String content = entry.path("content").asText("");
            String summary = entry.path("summary").asText("");
            String id = entry.path("id").asText("");
            sb.append("  [").append(action).append("] ").append(target);
            if (!summary.isEmpty()) sb.append(": ").append(summary);
            else if (!content.isEmpty()) sb.append(": ").append(content);
            sb.append("  (id: ").append(id, 0, Math.min(8, id.length())).append(")\n");
        }
        return sb.toString().trim();
    }

    private String approvePending(String id) {
        boolean success = backendClient.approvePendingMemory("default", id);
        return success ? "✅ Approved memory write: " + id : "❌ Failed to approve (not found or already resolved)";
    }

    private String rejectPending(String id) {
        boolean success = backendClient.rejectPendingMemory("default", id);
        return success ? "🚫 Rejected memory write: " + id : "❌ Failed to reject (not found or already resolved)";
    }

    private String toggleApproval(String mode) {
        boolean enabled = "on".equalsIgnoreCase(mode);
        backendClient.setMemoryApproval(enabled);
        return "Write-approval gate: " + (enabled ? "ON ✅" : "OFF ❌");
    }

    private String addMemory(String text) {
        // Use the backend chat endpoint to trigger a memory tool call
        // For simplicity, store directly via the memory add API
        backendClient.setMemoryApproval(backendClient.isMemoryApprovalEnabled());
        return "Memory add is handled via the agent. Ask the agent to remember: " + text;
    }

    private String removeMemory(String text) {
        return "Memory remove is handled via the agent. Ask the agent to forget: " + text;
    }
}