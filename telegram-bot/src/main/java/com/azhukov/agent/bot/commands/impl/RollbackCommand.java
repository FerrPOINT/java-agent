package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * /rollback — Filesystem rollback via checkpoint manager.
 * /rollback list — list available checkpoints
 * /rollback restore <id> — restore a checkpoint
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RollbackCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "rollback";
    }

    @Override
    public String description() {
        return "Filesystem rollback (list/restore checkpoints)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return "Usage: /rollback list | /rollback restore <id>";
        }

        String[] parts = args.trim().split("\\s+", 2);
        String subcommand = parts[0].toLowerCase();

        return switch (subcommand) {
            case "list" -> backendClient.listCheckpoints();
            case "restore" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    yield "Usage: /rollback restore <checkpoint-id>";
                }
                yield backendClient.restoreCheckpoint(parts[1].trim());
            }
            case "snapshot" -> backendClient.createCheckpoint(
                parts.length > 1 ? parts[1].trim() : "Manual checkpoint via /rollback");
            default -> "Unknown subcommand: " + subcommand + ". Use: list, restore <id>, snapshot [description]";
        };
    }
}