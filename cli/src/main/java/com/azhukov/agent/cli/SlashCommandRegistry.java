package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of slash commands available in the CLI REPL.
 * <p>
 * Commands are keyed by their name (without the leading '/').
 * The REPL dispatches user input to {@link #execute(String, BackendClient, String)}.
 */
@Component
@Slf4j
public class SlashCommandRegistry {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    public SlashCommandRegistry() {
        registerAll();
    }

    /**
     * Execute a slash command line (e.g. "/reset", "/undo 3").
     *
     * @param input     the full command line (starts with '/')
     * @param client    backend REST client
     * @param sessionId current session ID
     * @return output text, or null if the input is not a slash command
     */
    public String execute(String input, BackendClient client, String sessionId) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        String trimmed = input.substring(1).strip();
        if (trimmed.isEmpty()) {
            return "Empty command. Type /help for available commands.";
        }

        // Split into command name and args
        String name;
        String args = "";
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            name = trimmed.substring(0, spaceIdx);
            args = trimmed.substring(spaceIdx + 1).strip();
        } else {
            name = trimmed;
        }

        SlashCommand cmd = commands.get(name);
        if (cmd == null) {
            return "Unknown command: /" + name + "\nType /help for available commands.";
        }
        try {
            return cmd.execute(args, client, sessionId);
        } catch (Exception e) {
            log.error("Command /{} failed: {}", name, e.getMessage(), e);
            return "Error executing /" + name + ": " + e.getMessage();
        }
    }

    /**
     * Check if the input is a known slash command.
     */
    public boolean isSlashCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return false;
        }
        String trimmed = input.substring(1).strip();
        int spaceIdx = trimmed.indexOf(' ');
        String name = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
        return commands.containsKey(name);
    }

    /**
     * Get all registered command names.
     */
    public List<String> getCommandNames() {
        return List.copyOf(commands.keySet());
    }

    /**
     * Get the description for a command by name.
     *
     * @param name command name (without leading '/')
     * @return description string, or empty string if not found
     */
    public String getCommandDescription(String name) {
        return descriptions.getOrDefault(name, "");
    }

    /**
     * Get a sorted map of all command names to their descriptions.
     */
    public Map<String, String> getCommandDescriptions() {
        return new TreeMap<>(descriptions);
    }

    private void register(String name, String description, SlashCommand command) {
        commands.put(name, command);
        descriptions.put(name, description);
    }

    private void registerAll() {
        // ── Session management ──
        register("new", "Create a new chat session (use /new <uuid> to specify)", (args, client, sessionId) ->
            "New session started. Session ID: " + sessionId);

        register("reset", "Reset the current session", (args, client, sessionId) ->
            client.resetSession(sessionId));

        register("sessions", "List all sessions for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args;
            JsonNode sessions = client.listSessions(userId);
            return client.prettyPrint(sessions);
        });

        register("status", "Show current session status", (args, client, sessionId) ->
            "Current session: " + sessionId + "\nBackend: " + (client.health() ? "UP" : "DOWN"));

        register("context", "Show conversation context for the current session", (args, client, sessionId) -> {
            JsonNode ctx = client.getContext(sessionId);
            return ctx != null ? client.prettyPrint(ctx) : "No context available.";
        });

        register("compress", "Compress context (optionally with focus topic)", (args, client, sessionId) ->
            client.compressSession(sessionId, args.isBlank() ? null : args));

        register("undo", "Undo last N turns (default: 1)", (args, client, sessionId) -> {
            int turns = 1;
            if (!args.isBlank()) {
                try {
                    turns = Integer.parseInt(args.strip());
                } catch (NumberFormatException e) {
                    return "Invalid number: " + args;
                }
            }
            return client.undoTurns(sessionId, turns);
        });

        // ── Checkpoints ──
        register("checkpoint", "Create a checkpoint (optionally with description)", (args, client, sessionId) ->
            client.createCheckpoint(args.isBlank() ? "Manual checkpoint" : args));

        register("rollback", "Restore a checkpoint by ID", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /rollback <checkpoint-id>";
            }
            return client.restoreCheckpoint(args.strip());
        });

        // ── Memory & skills ──
        register("memory", "Show agent memory entries", (args, client, sessionId) -> {
            JsonNode mem = client.getMemory();
            return client.prettyPrint(mem);
        });

        register("skills", "List available agent skills", (args, client, sessionId) -> {
            JsonNode skills = client.getSkills();
            return client.prettyPrint(skills);
        });

        register("bundles", "List available bundles", (args, client, sessionId) -> {
            JsonNode bundles = client.listBundles();
            return client.prettyPrint(bundles);
        });

        // ── Approve / deny ──
        register("approve", "Approve pending action (use 'all' to approve all)", (args, client, sessionId) -> {
            boolean all = "all".equalsIgnoreCase(args.strip());
            String scope = all ? null : (args.isBlank() ? null : args.strip());
            return client.approve(all, scope);
        });

        register("deny", "Deny pending action (use 'all' to deny all)", (args, client, sessionId) ->
            client.deny("all".equalsIgnoreCase(args.strip())));

        // ── Steer ──
        register("steer", "Inject a steer note into the active turn", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /steer <message>";
            }
            return client.steer(args, sessionId);
        });

        // ── Restart / reload ──
        register("restart", "Restart the agent", (args, client, sessionId) ->
            client.restart());

        register("reload-mcp", "Reload MCP servers", (args, client, sessionId) ->
            client.reloadMcp());

        register("reload-skills", "Reload agent skills", (args, client, sessionId) ->
            client.reloadSkills());

        // ── Usage & insights ──
        register("usage", "Show token/cost usage for the current session", (args, client, sessionId) -> {
            JsonNode usage = client.getUsage(sessionId);
            return usage != null ? client.prettyPrint(usage) : "No usage data available.";
        });

        register("insights", "Show agent insights dashboard", (args, client, sessionId) -> {
            JsonNode insights = client.getInsights();
            return client.prettyPrint(insights);
        });

        register("agents", "List active agents", (args, client, sessionId) -> {
            JsonNode agents = client.listActiveAgents();
            return client.prettyPrint(agents);
        });

        register("health", "Check backend health", (args, client, sessionId) ->
            client.health() ? "Backend: UP ✓" : "Backend: DOWN ✗");

        // ── Model ──
        register("model", "Show or change the current model", (args, client, sessionId) ->
            "Use backend config to change model. Current CLI does not support model switching via REST.");

        // ── Version ──
        register("version", "Show CLI version info", (args, client, sessionId) ->
            "Java Agent CLI v0.0.1-SNAPSHOT\nJava 25, Spring Boot 4.1.0\nBackend: " +
            (client.health() ? "UP" : "DOWN"));

        // ── Help ──
        register("help", "Show this help message", (args, client, sessionId) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════\n");
            sb.append("  Available Commands\n");
            sb.append("═══════════════════════════════════════════════════\n");
            // Sort alphabetically for display
            Map<String, String> sorted = new TreeMap<>(descriptions);
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(String.format("  /%-16s %s%n", entry.getKey(), entry.getValue()));
            }
            sb.append("═══════════════════════════════════════════════════\n");
            sb.append("  Type any text (without /) to send a chat message.\n");
            sb.append("  Streaming is used by default for chat messages.\n");
            sb.append("═══════════════════════════════════════════════════");
            return sb.toString();
        });

        // ── Exit ──
        register("exit", "Exit the CLI", (args, client, sessionId) -> {
            System.out.println("Goodbye!");
            System.exit(0);
            return "Exiting...";
        });

        register("quit", "Exit the CLI (alias for /exit)", (args, client, sessionId) -> {
            System.out.println("Goodbye!");
            System.exit(0);
            return "Exiting...";
        });

        // ── Checkpoint listing (separate from creating) ──
        register("checkpoints", "List all checkpoints", (args, client, sessionId) ->
            client.listCheckpoints());

        // ── Delete checkpoint ──
        register("delete-checkpoint", "Delete a checkpoint by ID", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /delete-checkpoint <checkpoint-id>";
            }
            // Backend has a DELETE endpoint, but BackendClient doesn't expose it directly
            // Fall back to listing for now
            return "Use the backend REST API directly: DELETE /api/v1/agent/checkpoint/" + args.strip();
        });

        // ── Install bundle ──
        register("install", "Install a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /install <bundle-name>";
            }
            return "Use the backend REST API: POST /api/v1/agent/bundles/install with {\"bundleName\":\"" + args.strip() + "\"}";
        });

        register("uninstall", "Uninstall a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /uninstall <bundle-name>";
            }
            return "Use the backend REST API: POST /api/v1/agent/bundles/uninstall with {\"bundleName\":\"" + args.strip() + "\"}";
        });

        // ── Branch session ──
        register("branch", "Branch the current session (optionally with a name)", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /branch <branch-name>";
            }
            return "Use the backend REST API: POST /api/v1/agent/session/" + sessionId + "/branch?name=" + args.strip();
        });

        // ── Background task ──
        register("background", "Run a background task with a prompt", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /background <prompt>";
            }
            return "Use the backend REST API: POST /api/v1/agent/background with {\"prompt\":\"" + args.strip() + "\",\"sessionId\":\"" + sessionId + "\"}";
        });

        // ── Cron jobs ──
        register("cron", "List cron jobs", (args, client, sessionId) ->
            "Use the backend REST API: GET /api/v1/agent/cron");

        register("cron-pause", "Pause a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-pause <job-id>";
            return "Use the backend REST API: POST /api/v1/agent/cron/" + args.strip() + "/pause";
        });

        register("cron-resume", "Resume a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-resume <job-id>";
            return "Use the backend REST API: POST /api/v1/agent/cron/" + args.strip() + "/resume";
        });

        register("cron-delete", "Delete a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-delete <job-id>";
            return "Use the backend REST API: DELETE /api/v1/agent/cron/" + args.strip();
        });

        // ── Memory management ──
        register("memory-all", "List all memory entries for a user (default: 'default')", (args, client, sessionId) ->
            "Use the backend REST API: GET /api/v1/agent/memory/all/" + (args.isBlank() ? "default" : args.strip()));

        register("memory-pending", "List pending memory entries for a user (default: 'default')", (args, client, sessionId) ->
            "Use the backend REST API: GET /api/v1/agent/memory/pending/" + (args.isBlank() ? "default" : args.strip()));

        register("memory-approve", "Approve a pending memory entry", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-approve <userId> <entryId>";
            return "Use the backend REST API: POST /api/v1/agent/memory/approve";
        });

        register("memory-reject", "Reject a pending memory entry", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-reject <userId> <entryId>";
            return "Use the backend REST API: POST /api/v1/agent/memory/reject";
        });

        register("memory-delete", "Delete a memory entry", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-delete <userId> <entryId>";
            return "Use the backend REST API: DELETE /api/v1/agent/memory/" + args.strip();
        });

        // ── Tool approvals ──
        register("approvals", "List pending tool approvals", (args, client, sessionId) ->
            "Use the backend REST API: GET /api/v1/agent/approvals/pending");

        register("approve-tool", "Approve a pending tool for a session", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /approve-tool <sessionId>";
            return "Use the backend REST API: POST /api/v1/agent/approvals/" + args.strip() + "/approve";
        });

        register("deny-tool", "Deny a pending tool for a session", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /deny-tool <sessionId>";
            return "Use the backend REST API: POST /api/v1/agent/approvals/" + args.strip() + "/deny";
        });

        // ── Clear screen ──
        register("clear", "Clear the terminal screen", (args, client, sessionId) -> {
            System.out.print("\033[2J\033[H");
            System.out.flush();
            return "";
        });

        log.info("SlashCommandRegistry initialized with {} commands", commands.size());
    }
}