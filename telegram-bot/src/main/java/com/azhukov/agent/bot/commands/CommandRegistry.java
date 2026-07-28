package com.azhukov.agent.bot.commands;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all slash-command handlers.
 * Supports lookup by command name and generates help text listing all registered commands.
 */
@Component
public class CommandRegistry {

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

    public CommandRegistry(List<CommandHandler> handlers) {
        for (CommandHandler handler : handlers) {
            this.handlers.put(handler.name(), handler);
        }
    }

    /** Look up a handler by command name (without leading slash). */
    public CommandHandler get(String name) {
        return handlers.get(name);
    }

    /** Check whether a command with the given name is registered. */
    public boolean has(String name) {
        return handlers.containsKey(name);
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