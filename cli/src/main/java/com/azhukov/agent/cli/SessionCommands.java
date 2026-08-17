package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * c8: Session management slash commands.
 * <p>
 * Includes: new, sessions, status, context, compress, undo, checkpoint,
 * rollback, checkpoints, delete-checkpoint, branch, background, resume, save,
 * history, goal, subgoal, export, title.
 */
@Component
@RequiredArgsConstructor
public class SessionCommands implements CommandGroup {

    private final CliState cliState;
    private final SessionStore sessionStore;

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        // ── Session management ──
        registry.register("new", "Create a new chat session (use /new <uuid> to specify)", (args, client, sessionId) -> {
            String newSessionId;
            if (!args.isBlank()) {
                newSessionId = args.strip();
            } else {
                newSessionId = client.createSession();
            }
            if (newSessionId == null || newSessionId.isBlank()) {
                return "Failed to create new session (backend unavailable or returned empty ID).";
            }
            cliState.setCurrentSessionId(newSessionId);
            return "New session started. Session ID: " + newSessionId;
        });

        registry.register("sessions", "List all sessions for a user (default: 'default')", (args, client, sessionId) -> {
            String userId = args.isBlank() ? "default" : args;
            JsonNode sessions = client.listSessions(userId);
            return client.prettyPrint(sessions);
        });

        registry.register("status", "Show current session status", (args, client, sessionId) ->
            "Current session: " + sessionId + "\nBackend: " + (client.health() ? "UP" : "DOWN"));

        registry.register("context", "Show conversation context for the current session", (args, client, sessionId) -> {
            JsonNode ctx = client.getContext(sessionId);
            return ctx != null ? client.prettyPrint(ctx) : "No context available.";
        });

        registry.register("compress", "Compress context (optionally with focus topic)", (args, client, sessionId) ->
            client.compressSession(sessionId, args.isBlank() ? null : args));

        registry.register("undo", "Undo last N turns (default: 1)", (args, client, sessionId) -> {
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
        registry.register("checkpoint", "Create a checkpoint (optionally with description)", (args, client, sessionId) ->
            client.createCheckpoint(args.isBlank() ? "Manual checkpoint" : args));

        registry.register("rollback", "Restore a checkpoint by ID", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /rollback <checkpoint-id>";
            }
            return client.restoreCheckpoint(args.strip());
        });

        registry.register("checkpoints", "List all checkpoints", (args, client, sessionId) ->
            client.listCheckpoints());

        registry.register("delete-checkpoint", "Delete a checkpoint by ID", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /delete-checkpoint <checkpoint-id>";
            }
            return client.deleteCheckpoint(args.strip());
        });

        // ── Branch / background ──
        registry.register("branch", "Branch the current session (optionally with a name)", (args, client, sessionId) ->
            client.branchSession(sessionId, args.isBlank() ? null : args.strip()));

        registry.register("background", "Run a background task with a prompt", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /background <prompt>";
            }
            return client.backgroundTask(args.strip(), sessionId);
        });

        // ── Resume ──
        registry.register("resume", "Resume a previous session: /resume <sessionId> or /resume to list sessions", (args, client, sessionId) -> {
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
            String targetSessionId = args.strip();
            cliState.setCurrentSessionId(targetSessionId);
            return "Switched to session: " + targetSessionId;
        });

        // ── Save ──
        registry.register("save", "Save current session ID to disk", (args, client, sessionId) -> {
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

        // ── History ──
        registry.register("history", "Show conversation history for the current session", (args, client, sessionId) -> {
            JsonNode ctx = client.getContext(sessionId);
            if (ctx == null) return "No history available.";
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

        // ── Goal ──
        registry.register("goal", "Set or show the current goal: /goal [text|pause|resume|clear]", (args, client, sessionId) -> {
            String sub = args.strip().toLowerCase();
            if (sub.isBlank()) {
                return client.getGoal(sessionId);
            }
            switch (sub) {
                case "pause" -> { return client.pauseGoal(sessionId); }
                case "resume" -> { return client.resumeGoal(sessionId); }
                case "clear" -> {
                    cliState.setActiveGoal("");
                    return client.clearGoal(sessionId);
                }
                default -> {
                    String goalText = args.strip();
                    cliState.setActiveGoal(goalText);
                    return client.setGoal(sessionId, goalText);
                }
            }
        });

        registry.register("subgoal", "Add criteria to active goal: /subgoal <criteria>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String goal = cliState.getActiveGoal();
                return goal.isBlank() ? "No active goal set. Use /goal <text> first." : "Active goal: " + goal;
            }
            return client.addSubgoal(sessionId, args.strip());
        });

        // ── Title ──
        registry.register("title", "Set session title: /title <text>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                var entry = sessionStore.getSession(sessionId);
                return entry != null ? "Current title: " + entry.title : "No title set. Use /title <text>";
            }
            sessionStore.setTitle(sessionId, args.strip());
            return client.setTitle(sessionId, args.strip());
        });

        // ── Export ──
        registry.register("export", "Export the current session as JSON", (args, client, sessionId) -> {
            String data = client.exportSession(sessionId);
            if (args.isBlank()) {
                return data;
            }
            try {
                java.nio.file.Path path = java.nio.file.Path.of(args.strip());
                java.nio.file.Files.writeString(path, data);
                return "Session exported to: " + path.toAbsolutePath();
            } catch (Exception e) {
                return "Error writing file: " + e.getMessage() + "\n\n" + data;
            }
        });
    }
}