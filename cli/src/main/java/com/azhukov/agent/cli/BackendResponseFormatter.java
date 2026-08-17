package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Presentation layer that formats backend {@link JsonNode} responses into
 * human-readable strings for CLI display.
 * <p>
 * This separates transport ({@link BackendClient} — HTTP calls, error handling)
 * from presentation (this class — StringBuilder formatting, field extraction).
 * <p>
 * All methods accept a {@link JsonNode} (or null) and return a formatted String.
 * Null/empty/missing inputs produce sensible default messages rather than NPEs.
 */
@Component
@RequiredArgsConstructor
public class BackendResponseFormatter {

    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Generic
    // ------------------------------------------------------------------

    /**
     * Pretty-print any JsonNode using Jackson's default pretty printer.
     */
    public String prettyPrint(JsonNode node) {
        if (node == null) return "null";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // ------------------------------------------------------------------
    // Cron jobs
    // ------------------------------------------------------------------

    /**
     * Format a cron jobs JSON array as a human-readable list.
     */
    public String formatCronJobs(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "No cron jobs found.";
        }
        StringBuilder sb = new StringBuilder("Cron jobs:\n");
        for (JsonNode node : array) {
            String id = node.path("id").asText();
            String jobName = node.path("name").asText();
            String schedule = node.path("schedule").asText();
            boolean enabled = node.path("enabled").asBoolean();
            sb.append(String.format("- %s | %s | %s | %s%n", id, jobName, schedule,
                enabled ? "enabled" : "paused"));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------

    /**
     * Format a checkpoints JSON array as a human-readable list.
     */
    public String formatCheckpoints(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "No checkpoints found.";
        }
        StringBuilder sb = new StringBuilder("Checkpoints:\n");
        for (JsonNode node : array) {
            String id = node.path("id").asText();
            String desc = node.path("description").asText();
            int files = node.path("fileCount").asInt();
            sb.append(String.format("- %s | %s | %d files%n", id, desc, files));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    /**
     * Format the agent configuration JSON object.
     */
    public String formatConfig(JsonNode node) {
        if (node == null) {
            return "Config: no response from backend.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Agent config:\n");
        sb.append("  Name: ").append(node.path("name").asText("unknown")).append("\n");
        sb.append("  Model: ").append(node.path("model").asText("unknown")).append("\n");
        sb.append("  Provider: ").append(node.path("provider").asText("unknown")).append("\n");
        sb.append("  Base URL: ").append(node.path("baseUrl").asText("unknown")).append("\n");
        sb.append("  Max turns: ").append(node.path("maxTurns").asInt(-1)).append("\n");
        sb.append("  Max model calls/turn: ").append(node.path("maxModelCallsPerTurn").asInt(-1)).append("\n");
        sb.append("  Max tokens: ").append(node.path("maxTokens").asInt(-1)).append("\n");
        sb.append("  Temperature: ").append(node.path("temperature").asDouble(-1)).append("\n");
        sb.append("  Timeout: ").append(node.path("timeoutSeconds").asInt(-1)).append("s\n");
        sb.append("  Reasoning config: ").append(node.path("reasoningConfig").asText("unknown")).append("\n");
        sb.append("  Features:\n");
        JsonNode features = node.path("features");
        features.fieldNames().forEachRemaining(name ->
            sb.append("    ").append(name).append(": ")
              .append(features.path(name).asBoolean(false) ? "ON" : "OFF").append("\n"));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Doctor
    // ------------------------------------------------------------------

    /**
     * Format the doctor diagnostics JSON object.
     */
    public String formatDoctor(JsonNode node) {
        if (node == null) {
            return "Doctor: no response from backend.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Doctor report:\n");
        sb.append("  Backend: ").append(node.path("status").asText("unknown")).append("\n");
        sb.append("  Name: ").append(node.path("name").asText("unknown")).append("\n");
        sb.append("  Version: ").append(node.path("version").asText("unknown")).append("\n");
        sb.append("  Model: ").append(node.path("model").asText("unknown")).append("\n");
        sb.append("  Provider: ").append(node.path("provider").asText("unknown")).append("\n");
        sb.append("  Max turns: ").append(node.path("maxTurns").asInt(-1)).append("\n");
        sb.append("  Max model calls/turn: ").append(node.path("maxModelCallsPerTurn").asInt(-1)).append("\n");
        sb.append("  Memory: ").append(node.path("memoryEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
        sb.append("  TTS: ").append(node.path("ttsEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
        sb.append("  Transcription: ").append(node.path("transcriptionEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
        sb.append("  Skills loaded: ").append(node.path("skillCount").asInt(-1)).append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Credits
    // ------------------------------------------------------------------

    /**
     * Format the credits/cost summary JSON object.
     */
    public String formatCredits(JsonNode node) {
        if (node == null) {
            return "No credits data.";
        }
        StringBuilder sb = new StringBuilder("Credits summary:\n");
        sb.append("  Total cost: $").append(node.path("totalCost").asDouble(0)).append("\n");
        sb.append("  Total tokens: ").append(node.path("totalTokens").asInt(0)).append("\n");
        sb.append("  Total messages: ").append(node.path("totalMessages").asInt(0));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Curator status
    // ------------------------------------------------------------------

    /**
     * Format the curator status JSON object.
     */
    public String formatCuratorStatus(JsonNode node) {
        if (node == null) {
            return "No curator status.";
        }
        StringBuilder sb = new StringBuilder("Curator status:\n");
        sb.append("  Enabled: ").append(node.path("enabled").asBoolean(false)).append("\n");
        sb.append("  Paused: ").append(node.path("paused").asBoolean(false)).append("\n");
        sb.append("  Dry run: ").append(node.path("dryRun").asBoolean(false)).append("\n");
        sb.append("  Interval (hours): ").append(node.path("intervalHours").asInt(0)).append("\n");
        sb.append("  Min idle (hours): ").append(node.path("minIdleHours").asInt(0)).append("\n");
        sb.append("  Stale after (days): ").append(node.path("staleAfterDays").asInt(0)).append("\n");
        sb.append("  Archive after (days): ").append(node.path("archiveAfterDays").asInt(0));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Kanban
    // ------------------------------------------------------------------

    /**
     * Format the kanban board JSON array.
     */
    public String formatKanban(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "Kanban board is empty.";
        }
        StringBuilder sb = new StringBuilder("Kanban board:\n");
        for (JsonNode item : array) {
            String id = item.path("id").asText("?");
            String title = item.path("title").asText("?");
            String status = item.path("status").asText("?");
            String priority = item.path("priority").asText("?");
            sb.append("  [").append(status).append("] ")
                .append(title)
                .append(" (").append(priority).append(", id: ").append(id).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------
    // Codex runtime
    // ------------------------------------------------------------------

    /**
     * Format the codex runtime status JSON object.
     */
    public String formatCodexRuntime(JsonNode node) {
        if (node == null) {
            return "No runtime data.";
        }
        StringBuilder sb = new StringBuilder("Codex runtime:\n");
        sb.append("  Model: ").append(node.path("model").asText("?")).append("\n");
        sb.append("  Provider: ").append(node.path("provider").asText("?")).append("\n");
        sb.append("  Max retries: ").append(node.path("maxRetries").asInt(0)).append("\n");
        sb.append("  Max tokens: ").append(node.path("maxTokens").asInt(0)).append("\n");
        sb.append("  Timeout (seconds): ").append(node.path("timeoutSeconds").asInt(0));
        String override = node.path("modelOverride").asText(null);
        if (override != null) {
            sb.append("\n  Model override: ").append(override);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Goal
    // ------------------------------------------------------------------

    /**
     * Format the goal from a session context JSON object.
     */
    public String formatGoal(JsonNode node) {
        if (node == null) return "No goal set.";
        String goal = node.path("goal").asText(null);
        boolean paused = node.path("goalPaused").asBoolean(false);
        if (goal == null || goal.isBlank()) return "No goal set.";
        return "Current goal: " + goal + (paused ? " (paused)" : "");
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    /**
     * Format the plan/todo list from a session context JSON object.
     */
    public String formatPlan(JsonNode node) {
        if (node == null) return "No plan available.";
        JsonNode plan = node.path("plan");
        if (plan.isMissingNode() || plan.isNull()) return "No plan set for this session.";
        if (plan.isArray()) {
            StringBuilder sb = new StringBuilder("Current plan:\n");
            int i = 1;
            for (JsonNode item : plan) {
                String text = item.path("text").asText(item.asText("?"));
                boolean done = item.path("done").asBoolean(false);
                sb.append(String.format("  %d. [%s] %s%n", i++, done ? "x" : " ", text));
            }
            return sb.toString().stripTrailing();
        }
        return "Plan: " + plan.asText();
    }

    // ------------------------------------------------------------------
    // History (from /history command)
    // ------------------------------------------------------------------

    /**
     * Format a session context JSON object as a conversation history summary.
     */
    public String formatHistory(JsonNode ctx, String sessionId) {
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
    }

    // ------------------------------------------------------------------
    // Session list (from /resume command)
    // ------------------------------------------------------------------

    /**
     * Format a sessions JSON array as a human-readable list for /resume.
     */
    public String formatSessionList(JsonNode sessions) {
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
}