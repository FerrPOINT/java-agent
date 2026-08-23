package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * c8: Utility, local-state, and miscellaneous slash commands.
 * <p>
 * Includes: help, exit, quit, version, clear, redraw, profile, whoami,
 * statusbar, editor, image, debug, snapshot, personality, queue, retry,
 * verbose, yolo, busy, skills, bundles, install, uninstall.
 */
@Component
@RequiredArgsConstructor
public class UtilityCommands implements CommandGroup {

    private final CliState cliState;

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        // ── Help ──
        registry.register("help", "Show this help message", (args, client, sessionId) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════\n");
            sb.append("  Available Commands\n");
            sb.append("═══════════════════════════════════════════════════\n");
            Map<String, String> sorted = new TreeMap<>(registry.getCommandDescriptions());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(String.format("  /%-16s %s%n", entry.getKey(), entry.getValue()));
            }
            Map<String, String> aliases = registry.getAliases();
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

        // ── Exit / quit ──
        registry.register("exit", "Exit the CLI", (args, client, sessionId) -> {
            throw new ExitCliException("Goodbye!");
        });

        registry.register("quit", "Exit the CLI (alias for /exit)", (args, client, sessionId) -> {
            throw new ExitCliException("Goodbye!");
        });

        // ── Version ──
        registry.register("version", "Show CLI version info", (args, client, sessionId) ->
            "Java Agent CLI v0.0.1-SNAPSHOT\nJava 25, Spring Boot 4.1.0\nBackend: " +
            (client.health() ? "UP" : "DOWN"));

        // ── Clear screen / redraw ──
        registry.register("clear", "Clear the terminal screen", (args, client, sessionId) -> {
            System.out.print("\033[2J\033[H");
            System.out.flush();
            return "";
        });

        registry.register("redraw", "Force a full UI repaint (recovers from terminal drift)", (args, client, sessionId) -> {
            System.out.print("\033[2J\033[H");
            System.out.flush();
            return "Screen redrawn.";
        });

        // ── Profile / whoami / statusbar ──
        registry.register("profile", "Show active profile name and home directory", (args, client, sessionId) -> {
            String profile = cliState.getUserProfile();
            String homeDir = System.getProperty("user.home");
            return "Active profile: " + profile + "\nHome directory: " + homeDir;
        });

        registry.register("whoami", "Show your slash command access level", (args, client, sessionId) ->
            "User: default\nProfile: " + cliState.getUserProfile() + "\nAccess: user");

        registry.register("statusbar", "Toggle the context/model status bar", (args, client, sessionId) ->
            "Status bar: toggled (use /config to see current state)");

        // ── Editor / image ──
        registry.register("editor", "Open an external editor for multi-line input", (args, client, sessionId) -> {
            try {
                String edited = ExternalEditor.edit(args.isBlank() ? null : args);
                return edited != null ? edited : "Editor returned no content.";
            } catch (Exception e) {
                return "Editor error: " + e.getMessage();
            }
        });

        registry.register("image", "Attach a local image file for your next prompt: /image <path>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /image <file-path>";
            String path = args.strip();
            try {
                java.nio.file.Path imgPath = java.nio.file.Path.of(path);
                if (!java.nio.file.Files.exists(imgPath)) {
                    return "File not found: " + path;
                }
                return "Image attachment not yet supported in CLI. Use the Telegram bot to send images.\n"
                    + "File verified: " + imgPath.toAbsolutePath();
            } catch (Exception e) {
                return "Error attaching image: " + e.getMessage();
            }
        });

        // ── Debug ──
        registry.register("debug", "Toggle debug mode or upload debug report: /debug [on|off|report]", (args, client, sessionId) -> {
            String sub = args.strip().toLowerCase();
            switch (sub) {
                case "on" -> {
                    cliState.setDebugMode(true);
                    return "Debug mode: ON";
                }
                case "off" -> {
                    cliState.setDebugMode(false);
                    return "Debug mode: OFF";
                }
                case "report" -> {
                    return client.uploadDebugReport();
                }
                case "" -> {
                    boolean debug = cliState.toggleDebugMode();
                    return "Debug mode: " + (debug ? "ON" : "OFF");
                }
                default -> {
                    return "Usage: /debug [on|off|report]";
                }
            }
        });

        // ── Snapshot / personality / queue / retry ──
        registry.register("snapshot", "Create a state snapshot (optionally with description)", (args, client, sessionId) ->
            client.createSnapshot(sessionId, args.isBlank() ? null : args.strip()));

        registry.register("personality", "Set personality: /personality <text>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String p = cliState.getPersonality();
                return p.isBlank() ? "No personality set. Use /personality <text>" : "Current personality: " + p;
            }
            cliState.setPersonality(args.strip());
            return client.setPersonality(sessionId, args.strip());
        });

        registry.register("queue", "Queue a prompt for next turn: /queue <prompt>", (args, client, sessionId) -> {
            if (args.isBlank()) {
                String q = cliState.getQueuedPrompt();
                return q != null ? "Queued: " + q : "No prompt queued. Use /queue <prompt>";
            }
            cliState.setQueuedPrompt(args.strip());
            return client.queuePrompt(sessionId, args.strip());
        });

        registry.register("retry", "Resend the last user message to the agent", (args, client, sessionId) -> {
            String lastMsg = cliState.getLastUserMessage();
            if (lastMsg == null || lastMsg.isBlank()) {
                return "No previous message to retry.";
            }
            return client.retry(sessionId, lastMsg);
        });

        // ── Verbose / yolo / busy ──
        registry.register("verbose", "Cycle tool progress display (off→new→all→verbose)", (args, client, sessionId) -> {
            CliState.VerboseMode mode = cliState.cycleVerboseMode();
            return "Verbose mode: " + mode.name().toLowerCase() + "\n" +
                "(off=no tool output, new=only new tools, all=all tools, verbose=full details)";
        });

        registry.register("yolo", "Toggle YOLO mode (skip approvals)", (args, client, sessionId) -> {
            boolean yolo = cliState.toggleYoloMode();
            return "YOLO mode: " + (yolo ? "ON ⚡ (approvals skipped)" : "OFF (approvals required)");
        });

        registry.register("busy", "Control Enter behavior while agent is working: /busy [queue|steer|interrupt|status]", (args, client, sessionId) -> {
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

        // ── Skills / bundles / install / uninstall ──
        registry.register("skills", "List available agent skills", (args, client, sessionId) -> {
            JsonNode skills = client.getSkills();
            return client.prettyPrint(skills);
        });

        // Skills hub (SIMPLIFIED Hermes parity): /skills-hub [query] lists or
        // searches skills in the configured hub repo (default FerrPOINT/skills);
        // /skills-install <name> installs it locally after a threat scan.
        registry.register("skills-hub", "List or search hub skills: /skills-hub [query]", (args, client, sessionId) -> {
            JsonNode result = args.isBlank() ? client.hubList() : client.hubSearch(args.strip());
            return client.prettyPrint(result);
        });

        registry.register("skills-install", "Install a skill from the hub: /skills-install <name> [overwrite]", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /skills-install <name> [overwrite]";
            String[] parts = args.split("\\s+");
            boolean overwrite = parts.length > 1 && "overwrite".equalsIgnoreCase(parts[1]);
            return client.hubInstall(parts[0], overwrite);
        });

        registry.register("bundles", "List available bundles", (args, client, sessionId) -> {
            JsonNode bundles = client.listBundles();
            return client.prettyPrint(bundles);
        });

        registry.register("install", "Install a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /install <bundle-name>";
            }
            return client.installBundle(args.strip());
        });

        registry.register("uninstall", "Uninstall a bundle by name", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /uninstall <bundle-name>";
            }
            return client.uninstallBundle(args.strip());
        });

        // ── Aliases owned by the utility group ──
        registry.registerAlias("q", "queue");
        registry.registerAlias("s", "steer");
        registry.registerAlias("bg", "background");
        registry.registerAlias("snap", "checkpoint");
        registry.registerAlias("reset", "new");
        registry.registerAlias("fork", "branch");
        registry.registerAlias("v", "version");
        registry.registerAlias("sb", "statusbar");
    }
}