package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class WhoamiCommand implements CommandHandler {

    private final BotProperties properties;
    private final SlashAccessPolicy slashAccessPolicy;

    public WhoamiCommand(BotProperties properties, SlashAccessPolicy slashAccessPolicy) {
        this.properties = properties;
        this.slashAccessPolicy = slashAccessPolicy;
    }

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public String description() {
        return "Show your user info";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String accessLevel = slashAccessPolicy.accessLevel(event.userId());
        boolean authorized = properties.getAuth().getAllowedUserIds().contains(String.valueOf(event.userId()))
            || properties.getAuth().isAllowByDefault();
        return "User info:\n"
            + "  User ID: " + event.userId() + "\n"
            + "  Username: " + (event.username() != null ? "@" + event.username() : "unknown") + "\n"
            + "  Chat ID: " + event.chatId() + "\n"
            + "  Authorized: " + authorized + "\n"
            + "  Slash access: " + accessLevel;
    }
}