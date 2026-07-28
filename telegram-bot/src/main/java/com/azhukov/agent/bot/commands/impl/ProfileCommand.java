package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.1: /profile — Show active profile name and home directory from BotProperties.
 */
@Component
public class ProfileCommand implements CommandHandler {

    private final BotProperties properties;

    public ProfileCommand(BotProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public String description() {
        return "Show active profile and home directory";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String homeChatId = properties.getHomeChatId();
        return "Profile info:\n"
            + "  Agent name: " + properties.getAgentName() + "\n"
            + "  Working directory: " + properties.getWorkingDirectory() + "\n"
            + "  Backend URL: " + properties.getBackendUrl() + "\n"
            + "  Home chat: " + (homeChatId != null && !homeChatId.isBlank() ? homeChatId : "not set");
    }
}