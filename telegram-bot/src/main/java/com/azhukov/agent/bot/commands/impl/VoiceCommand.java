package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.1: /voice — Voice mode (stub, not supported in this build).
 */
@Component
public class VoiceCommand implements CommandHandler {

    @Override
    public String name() {
        return "voice";
    }

    @Override
    public String description() {
        return "Voice mode (not supported)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Voice mode is not supported in this build.";
    }
}