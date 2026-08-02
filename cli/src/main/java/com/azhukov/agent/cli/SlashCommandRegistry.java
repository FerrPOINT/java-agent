package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of slash commands available in the CLI REPL.
 * <p>
 * Commands are keyed by their name (without the leading '/').
 * The REPL dispatches user input to {@link #execute(String, BackendClient, String)}.
 * <p>
 * Supports:
 * <ul>
 *   <li>Exact command matching</li>
 *   <li>Alias resolution (e.g. /q → /sessions, /reset → /new, /fork → /branch)</li>
 *   <li>Prefix matching: if exactly one command starts with the typed prefix, execute it</li>
 * </ul>
 */
@Component
@Slf4j
public class SlashCommandRegistry {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<String> dynamicSkillNames = new ArrayList<>();

    // P1-3: Destructive command confirmation state machine
    private final DestructiveCommandConfirmation destructiveConfirmation = new DestructiveCommandConfirmation();

    // P1-4: Shared CLI state for local-only settings
    private final CliState cliState = new CliState();

    // P1-5: Session store for local session persistence
    private final SessionStore sessionStore = new SessionStore();

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

        // C7: Resolve command via exact → alias → prefix matching
        String resolvedName = resolveCommand(name);
        if (resolvedName == null) {
            return "Unknown command: /" + name + "\nType /help for available commands.";
        }

        SlashCommand cmd = commands.get(resolvedName);
        if (cmd == null) {
            return "Unknown command: /" + name + "\nType /help for available commands.";
        }
        try {
            return cmd.execute(args, client, sessionId);
        } catch (Exception e) {
            log.error("Command /{} failed: {}", resolvedName, e.getMessage(), e);
            return "Error executing /" + resolvedName + ": " + e.getMessage();
        }
    }

    /**
     * C7: Resolve a command name via exact match → alias → prefix matching.
     * <p>
     * If the name is an exact command, return it.
     * If it's an alias, resolve to the target command.
     * If exactly one command starts with the name (prefix match), return it.
     * If multiple commands match the prefix, return null (ambiguous).
     *
     * @param name the typed command name (without '/')
     * @return the resolved command name, or null if not found/ambiguous
     */
    public String resolveCommand(String name) {
        // 1. Exact match
        if (commands.containsKey(name)) {
            return name;
        }
        // 2. Alias match
        String aliased = aliases.get(name);
        if (aliased != null && commands.containsKey(aliased)) {
            return aliased;
        }
        // 3. Prefix match
        List<String> matches = new ArrayList<>();
        for (String cmdName : commands.keySet()) {
            if (cmdName.startsWith(name)) {
                matches.add(cmdName);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        // No match or ambiguous
        return null;
    }

    /**
     * C7: Check if input resolves to a known command (exact, alias, or prefix).
     */
    public boolean isSlashCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return false;
        }
        String trimmed = input.substring(1).strip();
        int spaceIdx = trimmed.indexOf(' ');
        String name = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
        return resolveCommand(name) != null;
    }

    /**
     * C7: Get all registered command names (for completion and help).
     */
    public List<String> getCommandNames() {
        return List.copyOf(commands.keySet());
    }

    /**
     * C7: Get all registered aliases (for completion).
     */
    public Map<String, String> getAliases() {
        return Map.copyOf(aliases);
    }

    /**
     * Get the description for a command by name.
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

    /**
     * C6: Register a dynamic skill command.
     */
    public void registerDynamicSkill(String skillName) {
        if (commands.containsKey(skillName) || aliases.containsKey(skillName)) {
            return; // Don't overwrite existing commands
        }
        dynamicSkillNames.add(skillName);
        register(skillName, "Skill: " + skillName, (args, client, sessionId) -> {
            String content = client.getSkillContent(skillName);
            if (content == null || content.isBlank()) {
                return "Skill '" + skillName + "' not found or empty.";
            }
            return content;
        });
    }

    /**
     * C6: Clear dynamic skill commands (for refresh).
     */
    public void clearDynamicSkills() {
        for (String skillName : dynamicSkillNames) {
            commands.remove(skillName);
            descriptions.remove(skillName);
        }
        dynamicSkillNames.clear();
    }

    /**
     * C6: Get list of dynamic skill names.
     */
    public List<String> getDynamicSkillNames() {
        return List.copyOf(dynamicSkillNames);
    }

    private void register(String name, String description, SlashCommand command) {
        commands.put(name, command);
        descriptions.put(name, description);
    }

    /**
     * C7: Register an alias.
     */
    private void registerAlias(String alias, String target) {
        aliases.put(alias, target);
    }

    private void registerAll() {
        // ── Session management ──
        register("new", "Create a new chat session (use /new <uuid> to specify)", (args, client, sessionId) ->
            "New session started. Session ID: " + sessionId);

        // "reset" is an alias for "new" (C7) — no separate /reset command

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

        // ── Model (C1: fixed — now calls backend) ──
        register("model", "Show or change the current model (e.g. /model gpt-4o [provider])", (args, client, sessionId) -> {
            if (args.isBlank()) {
                // Show current model info
                return client.getCurrentModel(sessionId);
            }
            // Parse: /model <model-name> [provider-name]
            String[] parts = args.split("\\s+");
            String model = parts[0];
            String provider = parts.length > 1 ? parts[1] : null;
            return client.switchModel(sessionId, model, provider);
        });

        register("config", "Show backend configuration", (args, client, sessionId) ->
            client.config());

        register("doctor", "Run backend diagnostics", (args, client, sessionId) ->
            client.doctor());

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
            Map<String, String> sorted = new TreeMap<>(descriptions);
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(String.format("  /%-16s %s%n", entry.getKey(), entry.getValue()));
            }
            if (!aliases.isEmpty()) {
                sb.append("═══════════════════════════════════════════════════\n");
                sb.append("  Aliases:\n");
                Map<String, String> sortedAliases = new TreeMap<>(aliases);
                for (Map.Entry<String, String> entry : sortedAliases.entrySet()) {
                    sb.append(String.format("  /%-16s → /%s%n", entry.getKey(), entry.getValue()));
                }
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

        // ── Delete checkpoint (C2: fixed — now calls backend) ──
        register("delete-checkpoint", "Delete a checkpoint by ID", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /delete-checkpoint <checkpoint-id>";
            }
            return client.deleteCheckpoint(args.strip());
        });

        // ── Install bundle (C2: fixed — now calls backend) ──
        register("install", "Install a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /install <bundle-name>";
            }
            return client.installBundle(args.strip());
        });

        register("uninstall", "Uninstall a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /uninstall <bundle-name>";
            }
            return client.uninstallBundle(args.strip());
        });

        // ── Branch session (C2: fixed — now calls backend) ──
        register("branch", "Branch the current session (optionally with a name)", (args, client, sessionId) ->
            client.branchSession(sessionId, args.isBlank() ? null : args.strip()));

        // ── Background task (C2: fixed — now calls backend) ──
        register("background", "Run a background task with a prompt", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /background <prompt>";
            }
            return client.backgroundTask(args.strip(), sessionId);
        });

        // ── Cron jobs (C2: fixed — now calls backend) ──
        register("cron", "List cron jobs", (args, client, sessionId) ->
            client.listCronJobs());

        register("cron-pause", "Pause a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-pause <job-id>";
            return client.pauseCronJob(args.strip());
        });

        register("cron-resume", "Resume a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-resume <job-id>";
            return client.resumeCronJob(args.strip());
        });

        register("cron-delete", "Delete a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-delete <job-id>";
            return client.deleteCronJob(args.strip());
        });

        register("cron-create", "Create a cron job: /cron-create <name> <schedule> <prompt>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-create <name> <schedule> <prompt>";
            String[] parts = args.split("\\s+", 3);
            if (parts.length < 3) return "Usage: /cron-create <name> <schedule> <prompt>";
            return client.createCronJob(parts[0], parts[1], parts[2], null);
        });

        // ── Memory management (C2: fixed — now calls backend) ──
        register("memory-all", "List all memory entries for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args.strip();
            JsonNode mem = client.listAllMemory(userId);
            return client.prettyPrint(mem);
        });

        register("memory-pending", "List pending memory entries for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args.strip();
            JsonNode mem = client.listPendingMemory(userId);
            return client.prettyPrint(mem);
        });

        register("memory-approve", "Approve a pending memory entry: /memory-approve <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-approve <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-approve <userId> <entryId>";
            return client.approveMemory(parts[0], parts[1]);
        });

        register("memory-reject", "Reject a pending memory entry: /memory-reject <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-reject <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-reject <userId> <entryId>";
            return client.rejectMemory(parts[0], parts[1]);
        });

        register("memory-delete", "Delete a memory entry: /memory-delete <userId> <entryId>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /memory-delete <userId> <entryId>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /memory-delete <userId> <entryId>";
            return client.deleteMemory(parts[0], parts[1]);
        });

        // ── Tool approvals (C2: fixed — now calls backend) ──
        register("approvals", "List pending tool approvals", (args, client, sessionId) -> {
            JsonNode approvals = client.listPendingApprovals();
            return client.prettyPrint(approvals);
        });

        register("approve-tool", "Approve a pending tool for a session", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /approve-tool <sessionId>";
            return client.approveTool(args.strip());
        });

        register("deny-tool", "Deny a pending tool for a session", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /deny-tool <sessionId>";
            return client.denyTool(args.strip());
        });

        // ── C3: New commands ──

        register("stop", "Stop the agent's current turn", (args, client, sessionId) ->
            client.stopAgent(sessionId));

        register("history", "Show conversation history for the current session", (args, client, sessionId) -> {
            JsonNode ctx = client.getContext(sessionId);
            if (ctx == null) return "No history available.";
            // Format context nicely
            StringBuilder sb = new StringBuilder();
            int messageCount = ctx.path("messageCount").asInt(0);
            int tokenEstimate = ctx.path("tokenEstimate").asInt(0);
            sb.append("Session: ").append(sessionId).append("\n");
            sb.append("Messages: ").append(messageCount).append("\n");
            sb.append("Token estimate: ").append(tokenEstimate).append("\n");
            JsonNode toolsUsed = ctx.path("toolsUsed");
            if (toolsUsed.isArray() && !toolsUsed.isEmpty()) {
                sb.append("Tools used: ");
                for (JsonNode tool : toolsUsed) {
                    sb.append(tool.asText()).append(" ");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        });

        register("goal", "Set or show the current goal (stored locally)", (args, client, sessionId) -> {
            // Goal is stored locally — backend doesn't have a goal API yet
            if (args.isBlank()) {
                String currentGoal = goalStorage.get(sessionId);
                return currentGoal == null ? "No goal set. Use /goal <text> to set one." : "Current goal: " + currentGoal;
            }
            goalStorage.put(sessionId, args.strip());
            return "Goal set: " + args.strip();
        });

        register("resume", "Resume a previous session: /resume <sessionId> or /resume to list sessions", (args, client, sessionId) -> {
            if (args.isBlank()) {
                JsonNode sessions = client.listSessions("default");
                if (sessions == null || !sessions.isArray() || sessions.isEmpty()) {
                    return "No sessions found for user 'default'.\nUsage: /resume <sessionId>";
                }
                StringBuilder sb = new StringBuilder("Available sessions:\n");
                for (JsonNode s : sessions) {
                    String id = s.path("id").asText(s.path("sessionId").asText("?"));
                    String title = s.path("title").asText("(no title)");
                    sb.append("  ").append(id).append(" | ").append(title).append("\n");
                }
                sb.append("\nUse /resume <sessionId> to resume a session.");
                return sb.toString().trim();
            }
            // In a real implementation, this would switch the session ID
            // For now, show instructions
            return "To resume session " + args.strip() + ", restart CLI with --session.id=" + args.strip();
        });

        // ── C4: Session persistence ──
        register("save", "Save current session ID to disk", (args, client, sessionId) -> {
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), ".java-agent-cli");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path file = dir.resolve("session.txt");
                java.nio.file.Files.writeString(file, sessionId);
                return "Session saved: " + sessionId;
            } catch (Exception e) {
                return "Error saving session: " + e.getMessage();
            }
        });

        // ── Clear screen ──
        register("clear", "Clear the terminal screen", (args, client, sessionId) -> {
            System.out.print("\033[2J\033[H");
            System.out.flush();
            return "";
        });

        // ── P1-4: 15 New slash commands ──

        register("retry", "Resend the last user message to the agent", (args, client, sessionId) -> {
            String lastMsg = cliState.getLastUserMessage();
            if (lastMsg == null || lastMsg.isBlank()) {
                return "No previous message to retry.";
            }
            return client.retry(sessionId, lastMsg);
        });

        register("title", "Set session title: /title <text>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                var entry = sessionStore.getSession(sessionId);
                return entry != null ? "Current title: " + entry.title : "No title set. Use /title <text>";
            }
            sessionStore.setTitle(sessionId, args.strip());
            return client.setTitle(sessionId, args.strip());
        });

        register("queue", "Queue a prompt for next turn: /queue <prompt>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String q = cliState.getQueuedPrompt();
                return q != null ? "Queued: " + q : "No prompt queued. Use /queue <prompt>";
            }
            cliState.setQueuedPrompt(args.strip());
            return client.queuePrompt(sessionId, args.strip());
        });

        register("snapshot", "Create a state snapshot (optionally with description)", (args, client, sessionId) ->
            client.createSnapshot(sessionId, args.isBlank() ? null : args.strip()));

        register("personality", "Set personality: /personality <text>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String p = cliState.getPersonality();
                return p.isBlank() ? "No personality set. Use /personality <text>" : "Current personality: " + p;
            }
            cliState.setPersonality(args.strip());
            return client.setPersonality(sessionId, args.strip());
        });

        register("verbose", "Cycle tool progress display (off→new→all→verbose)", (args, client, sessionId) -> {
            CliState.VerboseMode mode = cliState.cycleVerboseMode();
            return "Verbose mode: " + mode.name().toLowerCase() + "\n" +
                "(off=no tool output, new=only new tools, all=all tools, verbose=full details)";
        });

        register("yolo", "Toggle YOLO mode (skip approvals)", (args, client, sessionId) -> {
            boolean yolo = cliState.toggleYoloMode();
            return "YOLO mode: " + (yolo ? "ON ⚡ (approvals skipped)" : "OFF (approvals required)");
        });

        register("reasoning", "Manage reasoning effort: /reasoning [none|minimal|low|medium|high|xhigh]", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Current reasoning effort: " + cliState.getReasoningEffort() + "\n" +
                    "Levels: " + String.join(", ", CliState.getValidReasoningLevels()) + "\n" +
                    "Use /reasoning <level> to set, or /reasoning cycle to cycle.";
            }
            if ("cycle".equalsIgnoreCase(args.strip())) {
                String level = cliState.cycleReasoningEffort();
                client.setReasoningEffort(sessionId, level);
                return "Reasoning effort: " + level;
            }
            if (!cliState.setReasoningEffortIfValid(args.strip())) {
                return "Invalid level: " + args + "\nValid levels: " +
                    String.join(", ", CliState.getValidReasoningLevels());
            }
            String level = cliState.getReasoningEffort();
            client.setReasoningEffort(sessionId, level);
            return "Reasoning effort: " + level;
        });

        register("fast", "Toggle fast mode", (args, client, sessionId) -> {
            boolean fast = cliState.toggleFastMode();
            client.setFastMode(sessionId, fast);
            return "Fast mode: " + (fast ? "ON ⚡" : "OFF");
        });

        register("voice", "Toggle voice mode: /voice [on|off|tts|status]", (args, client, sessionId) -> {
            String arg = args.strip().toLowerCase();
            switch (arg) {
                case "on" -> {
                    cliState.setVoiceMode(true);
                    client.setVoiceMode(sessionId, true);
                    return "Voice mode: ON";
                }
                case "off" -> {
                    cliState.setVoiceMode(false);
                    client.setVoiceMode(sessionId, false);
                    return "Voice mode: OFF";
                }
                case "tts" -> {
                    boolean tts = !cliState.isTtsEnabled();
                    cliState.setTtsEnabled(tts);
                    return "TTS: " + (tts ? "ON" : "OFF");
                }
                case "status", "" -> {
                    return "Voice mode: " + (cliState.isVoiceMode() ? "ON" : "OFF") + "\n" +
                        "TTS: " + (cliState.isTtsEnabled() ? "ON" : "OFF");
                }
                default -> {
                    return "Usage: /voice [on|off|tts|status]";
                }
            }
        });

        register("busy", "Control Enter behavior while agent is working: /busy [queue|steer|interrupt|status]", (args, client, sessionId) -> {
            String arg = args.strip().toLowerCase();
            switch (arg) {
                case "queue" -> {
                    cliState.setBusyMode(CliState.BusyMode.QUEUE);
                    return "Busy mode: QUEUE (Enter queues your message for next turn)";
                }
                case "steer" -> {
                    cliState.setBusyMode(CliState.BusyMode.STEER);
                    return "Busy mode: STEER (Enter injects a steer note)";
                }
                case "interrupt" -> {
                    cliState.setBusyMode(CliState.BusyMode.INTERRUPT);
                    return "Busy mode: INTERRUPT (Enter interrupts the agent)";
                }
                case "status", "" -> {
                    return "Busy mode: " + cliState.getBusyMode().name().toLowerCase();
                }
                default -> {
                    return "Usage: /busy [queue|steer|interrupt|status]";
                }
            }
        });

        register("tools", "List/disable/enable tools: /tools [list|disable <name>|enable <name>]", (args, client, sessionId) -> {
            if (args.isBlank()) {
                JsonNode tools = client.listTools(sessionId);
                return client.prettyPrint(tools);
            }
            String[] parts = args.split("\\s+", 2);
            String subCmd = parts[0].toLowerCase();
            switch (subCmd) {
                case "list" -> {
                    JsonNode tools = client.listTools(sessionId);
                    return client.prettyPrint(tools);
                }
                case "disable" -> {
                    if (parts.length < 2) return "Usage: /tools disable <tool-name>";
                    cliState.setToolEnabled(parts[1].strip(), false);
                    return client.toggleTool(sessionId, parts[1].strip(), false);
                }
                case "enable" -> {
                    if (parts.length < 2) return "Usage: /tools enable <tool-name>";
                    cliState.setToolEnabled(parts[1].strip(), true);
                    return client.toggleTool(sessionId, parts[1].strip(), true);
                }
                default -> {
                    return "Usage: /tools [list|disable <name>|enable <name>]";
                }
            }
        });

        register("browser", "Connect browser tools to CDP: /browser <cdp-url>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String url = cliState.getCdpUrl();
                return url.isBlank() ? "No CDP URL configured. Use /browser <cdp-url>" : "CDP URL: " + url;
            }
            cliState.setCdpUrl(args.strip());
            return client.connectBrowser(args.strip());
        });

        register("plugins", "List installed plugins", (args, client, sessionId) -> {
            JsonNode plugins = client.listPlugins();
            return client.prettyPrint(plugins);
        });

        register("subgoal", "Add criteria to active goal: /subgoal <criteria>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String goal = cliState.getActiveGoal();
                return goal.isBlank() ? "No active goal set. Use /goal <text> first." : "Active goal: " + goal;
            }
            return client.addSubgoal(sessionId, args.strip());
        });

        register("reload", "Reload skills and MCP servers", (args, client, sessionId) ->
            client.reloadAll());

        register("diff", "Compare two checkpoints: /diff <left-id> <right-id>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /diff <left-id> <right-id>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /diff <left-id> <right-id>";
            return client.diff(parts[0], parts[1]);
        });

        // ── Batch B: /credits, /curator ──
        register("credits", "Show credit/cost usage summary", (args, client, sessionId) ->
            client.getCredits());

        register("curator", "Curator management: /curator [status|run|pause|resume]", (args, client, sessionId) -> {
            String sub = args.strip().toLowerCase();
            if (sub.isBlank() || "status".equals(sub)) {
                return client.curatorStatus();
            }
            switch (sub) {
                case "run" -> { return client.curatorRun(); }
                case "pause" -> { return client.curatorPause(); }
                case "resume" -> { return client.curatorResume(); }
                default -> { return "Usage: /curator [status|run|pause|resume]"; }
            }
        });

        // ── Batch C: /kanban, /codex_runtime ──
        register("kanban", "Kanban board: /kanban [list|add <text>|done <id>|clear]", (args, client, sessionId) -> {
            String sub = args.strip().toLowerCase();
            if (sub.isBlank() || "list".equals(sub)) {
                return client.kanbanList();
            }
            String[] parts = args.split("\\s+", 2);
            switch (parts[0].toLowerCase()) {
                case "add" -> {
                    if (parts.length < 2) return "Usage: /kanban add <text>";
                    return client.kanbanAdd(parts[1].strip());
                }
                case "done" -> {
                    if (parts.length < 2) return "Usage: /kanban done <id>";
                    return client.kanbanDone(parts[1].strip());
                }
                case "clear" -> { return client.kanbanClear(); }
                default -> { return "Usage: /kanban [list|add <text>|done <id>|clear]"; }
            }
        });

        register("codex_runtime", "Codex runtime settings: /codex_runtime [status|model <name>|reset]", (args, client, sessionId) -> {
            String sub = args.strip().toLowerCase();
            if (sub.isBlank() || "status".equals(sub)) {
                return client.codexRuntimeStatus();
            }
            String[] parts = args.split("\\s+", 2);
            switch (parts[0].toLowerCase()) {
                case "model" -> {
                    if (parts.length < 2) return "Usage: /codex_runtime model <name>";
                    return client.codexRuntimeModel(parts[1].strip());
                }
                case "reset" -> { return client.codexRuntimeReset(); }
                default -> { return "Usage: /codex_runtime [status|model <name>|reset]"; }
            }
        });

        // ── C7: Aliases ──
        registerAlias("q", "queue");
        registerAlias("s", "steer");
        registerAlias("c", "cron");
        registerAlias("r", "reload");
        registerAlias("d", "diff");
        registerAlias("reset", "new");
        registerAlias("fork", "branch");
        registerAlias("bg", "background");
        registerAlias("snap", "checkpoint");

        log.info("SlashCommandRegistry initialized with {} commands, {} aliases",
            commands.size(), aliases.size());
    }

    // Simple in-memory goal storage for /goal command (C3)
    private final Map<String, String> goalStorage = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * P1-3: Get the destructive command confirmation instance.
     */
    public DestructiveCommandConfirmation getDestructiveConfirmation() {
        return destructiveConfirmation;
    }

    /**
     * P1-4: Get shared CLI state.
     */
    public CliState getCliState() {
        return cliState;
    }

    /**
     * P1-5: Get session store.
     */
    public SessionStore getSessionStore() {
        return sessionStore;
    }
}