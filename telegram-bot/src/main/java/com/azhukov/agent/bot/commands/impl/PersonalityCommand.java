package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * /personality — Set or show the agent personality (agent name).
 * /personality          — show current personality
 * /personality <name>   — set new personality
 * /personality reset    — reset to default
 */
@Component
public class PersonalityCommand implements CommandHandler {

    private static final String DEFAULT_PERSONALITY = "Джава агент";

    private final BotProperties properties;

    public PersonalityCommand(BotProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "personality";
    }

    @Override
    public String description() {
        return "Set or show agent personality";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return "Current personality: " + properties.getAgentName()
                + "\n\nUsage: /personality <name> — set personality"
                + "\n       /personality reset — reset to default";
        }

        if (args.equalsIgnoreCase("reset")) {
            properties.setAgentName(DEFAULT_PERSONALITY);
            return "Personality reset to: " + DEFAULT_PERSONALITY;
        }

        String name = args.trim();
        if (name.length() > 100) {
            return "Personality name too long (max 100 chars).";
        }

        properties.setAgentName(name);
        return "Personality set to: " + name;
    }
}