package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2.2: /platform — List connected platforms. For the Telegram bot, just shows "telegram".
 */
@Component
public class PlatformCommand implements CommandHandler {

    private final BotProperties properties;

    public PlatformCommand(BotProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "platform";
    }

    @Override
    public String description() {
        return "List connected platforms";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args != null && !args.isBlank() && !"list".equalsIgnoreCase(args.trim())) {
            return "Usage: /platform list";
        }

        // For the Telegram bot, only telegram is connected
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("telegram", "active");

        StringBuilder sb = new StringBuilder("Connected platforms:\n");
        for (Map.Entry<String, String> entry : platforms.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }
}