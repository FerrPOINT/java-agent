package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements CommandHandler {

    private final ObjectProvider<CommandRegistry> registryProvider;

    public HelpCommand(ObjectProvider<CommandRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show this help message";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        CommandRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            return registry.helpText();
        }
        return "No help available.";
    }
}