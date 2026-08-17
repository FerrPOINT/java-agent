package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * c8: Admin / diagnostic / backend-management slash commands.
 * <p>
 * Includes: config, doctor, health, usage, insights, agents, restart,
 * reload-mcp, reload-skills, reload, diff, credits, curator, kanban,
 * codex_runtime, plugins, toolsets, tools, browser, plan, gquota, platforms.
 */
@Component
@RequiredArgsConstructor
public class AdminCommands implements CommandGroup {

    private final CliState cliState;

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("config", "Show backend configuration", (args, client, sessionId) ->
            client.config());

        registry.register("doctor", "Run backend diagnostics", (args, client, sessionId) ->
            client.doctor());

        registry.register("health", "Check backend health", (args, client, sessionId) ->
            client.health() ? "Backend: UP ✓" : "Backend: DOWN ✗");

        registry.register("usage", "Show token/cost usage for the current session", (args, client, sessionId) -> {
            JsonNode usage = client.getUsage(sessionId);
            return usage != null ? client.prettyPrint(usage) : "No usage data available.";
        });

        registry.register("insights", "Show agent insights dashboard", (args, client, sessionId) -> {
            JsonNode insights = client.getInsights();
            return client.prettyPrint(insights);
        });

        registry.register("agents", "List active agents", (args, client, sessionId) -> {
            JsonNode agents = client.listActiveAgents();
            return client.prettyPrint(agents);
        });

        registry.register("restart", "Restart the agent", (args, client, sessionId) ->
            client.restart());

        registry.register("reload-mcp", "Reload MCP servers", (args, client, sessionId) ->
            client.reloadMcp());

        registry.register("reload-skills", "Reload agent skills", (args, client, sessionId) ->
            client.reloadSkills());

        registry.register("reload", "Reload skills and MCP servers", (args, client, sessionId) ->
            client.reloadAll());

        registry.register("diff", "Compare two checkpoints: /diff <left-id> <right-id>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /diff <left-id> <right-id>";
            String[] parts = args.split("\\s+");
            if (parts.length < 2) return "Usage: /diff <left-id> <right-id>";
            return client.diff(parts[0], parts[1]);
        });

        registry.register("credits", "Show credit/cost usage summary", (args, client, sessionId) ->
            client.getCredits());

        registry.register("curator", "Curator management: /curator [status|run|pause|resume]", (args, client, sessionId) -> {
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

        registry.register("kanban", "Kanban board: /kanban [list|add <text>|done <id>|clear]", (args, client, sessionId) -> {
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

        registry.register("codex_runtime", "Codex runtime settings: /codex_runtime [status|model <name>|reset]", (args, client, sessionId) -> {
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

        registry.register("plugins", "List configured MCP servers", (args, client, sessionId) -> {
            JsonNode plugins = client.listPlugins();
            return client.prettyPrint(plugins);
        });

        registry.register("toolsets", "List or manage toolsets: /toolsets [list|enable <name>|disable <name>]", (args, client, sessionId) -> {
            if (args.isBlank()) {
                JsonNode toolsets = client.listToolsets();
                return client.prettyPrint(toolsets);
            }
            String[] parts = args.split("\\s+", 2);
            String subCmd = parts[0].toLowerCase();
            switch (subCmd) {
                case "list" -> {
                    return client.prettyPrint(client.listToolsets());
                }
                case "enable" -> {
                    if (parts.length < 2) return "Usage: /toolsets enable <toolset-name>";
                    return client.toggleToolset(parts[1].strip(), true);
                }
                case "disable" -> {
                    if (parts.length < 2) return "Usage: /toolsets disable <toolset-name>";
                    return client.toggleToolset(parts[1].strip(), false);
                }
                default -> {
                    return "Usage: /toolsets [list|enable <name>|disable <name>]";
                }
            }
        });

        registry.register("tools", "List/disable/enable tools: /tools [list|disable <name>|enable <name>]", (args, client, sessionId) -> {
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

        registry.register("browser", "Connect browser tools to CDP: /browser <cdp-url>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String url = cliState.getCdpUrl();
                return url.isBlank() ? "No CDP URL configured. Use /browser <cdp-url>" : "CDP URL: " + url;
            }
            cliState.setCdpUrl(args.strip());
            return client.connectBrowser(sessionId, args.strip());
        });

        registry.register("plan", "Show the current plan for this session", (args, client, sessionId) ->
            client.getPlan(sessionId));

        registry.register("gquota", "Show Google Gemini Code Assist quota usage", (args, client, sessionId) -> {
            try {
                JsonNode quota = client.getInsights();
                return "Gemini quota usage:\n" + client.prettyPrint(quota);
            } catch (Exception e) {
                return "Error fetching quota: " + e.getMessage();
            }
        });

        registry.register("platforms", "Show gateway/messaging platform status", (args, client, sessionId) -> {
            try {
                String json = client.prettyPrint(client.listPlugins());
                return "Platform status:\n" + json;
            } catch (Exception e) {
                return "No platform data available.";
            }
        });

        // Aliases owned by the admin group
        registry.registerAlias("c", "cron");
        registry.registerAlias("r", "reload");
        registry.registerAlias("d", "diff");
    }
}