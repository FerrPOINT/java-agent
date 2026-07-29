package com.azhukov.agent.bot.commands;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all slash-command handlers.
 * Supports lookup by command name and generates help text listing all registered commands.
 * Includes alias support for Hermes parity.
 */
@Component
public class CommandRegistry {

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

    /** Command aliases — maps alias name to canonical command name. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("sethome", "set_home"),
        Map.entry("set-home", "set_home"),
        Map.entry("fork", "branch"),
        Map.entry("gateway", "platform"),
        Map.entry("platforms", "platform"),
        Map.entry("tasks", "agents"),
        Map.entry("codex-runtime", "codex_runtime"),
        Map.entry("reload-mcp", "reload_mcp"),
        Map.entry("reload-skills", "reload_skills"),
        Map.entry("suggest", "suggestions")
    );

    public CommandRegistry(List<CommandHandler> handlers) {
        for (CommandHandler handler : handlers) {
            this.handlers.put(handler.name(), handler);
        }
    }

    /** Look up a handler by command name (without leading slash). Resolves aliases. */
    public CommandHandler get(String name) {
        String resolved = ALIASES.getOrDefault(name, name);
        return handlers.get(resolved);
    }

    /** Check whether a command with the given name is registered. Resolves aliases. */
    public boolean has(String name) {
        String resolved = ALIASES.getOrDefault(name, name);
        return handlers.containsKey(resolved);
    }

    /** All registered handlers, in registration order. */
    public List<CommandHandler> all() {
        return List.copyOf(handlers.values());
    }

    /** Generate help text listing all commands. */
    public String helpText() {
        StringBuilder sb = new StringBuilder("Available commands:\n\n");
        for (CommandHandler handler : handlers.values()) {
            sb.append("/").append(handler.name())
              .append(" — ").append(handler.description())
              .append("\n");
        }
        sb.append("\nSend any text message to chat with the agent.");
        return sb.toString();
    }
}