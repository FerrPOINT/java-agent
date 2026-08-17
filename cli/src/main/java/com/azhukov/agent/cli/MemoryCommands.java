package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * c8: Memory management slash commands.
 * <p>
 * Includes: memory, memory-all, memory-pending, memory-approve, memory-reject,
 * memory-delete.
 */
@Component
public class MemoryCommands implements CommandGroup {

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("memory", "Show agent memory entries", (args, client, sessionId) -> {
            JsonNode mem = client.getMemory();
            return client.prettyPrint(mem);
        });

        registry.register("memory-all", "List all memory entries for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args.strip();
            JsonNode mem = client.listAllMemory(userId);
            return client.prettyPrint(mem);
        });

        registry.register("memory-pending", "List pending memory entries for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args.strip();
            JsonNode mem = client.listPendingMemory(userId);
            return client.prettyPrint(mem);
        });

        registry.register("memory-approve", "Approve a pending memory entry: /memory-approve <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-approve <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-approve <userId> <entryId>";
            return client.approveMemory(parts[0], parts[1]);
        });

        registry.register("memory-reject", "Reject a pending memory entry: /memory-reject <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-reject <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-reject <userId> <entryId>";
            return client.rejectMemory(parts[0], parts[1]);
        });

        registry.register("memory-delete", "Delete a memory entry: /memory-delete <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-delete <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-delete <userId> <entryId>";
            return client.deleteMemory(parts[0], parts[1]);
        });
    }
}