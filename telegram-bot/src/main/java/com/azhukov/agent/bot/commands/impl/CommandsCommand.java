package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommandsCommand implements CommandHandler {

    private final ObjectProvider<CommandRegistry> registryProvider;

    public CommandsCommand(ObjectProvider<CommandRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public String name() {
        return "commands";
    }

    @Override
    public String description() {
        return "List all available commands";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        CommandRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) return "No commands available.";
        List<CommandHandler> handlers = registry.all();
        StringBuilder sb = new StringBuilder("All commands (").append(handlers.size()).append("):\n\n");
        for (CommandHandler h : handlers) {
            sb.append("/").append(h.name()).append(" — ").append(h.description()).append("\n");
        }
        return sb.toString();
    }
}