package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class ModelCommand implements CommandHandler {

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Set or show the model override (usage: /model [name])";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            if (session != null && session.getModelOverride() != null) {
                return "Current model: " + session.getModelOverride();
            }
            return "Current model: default. Usage: /model <name> to set, /model to clear.";
        }
        return "Model set to: " + args;
    }
}