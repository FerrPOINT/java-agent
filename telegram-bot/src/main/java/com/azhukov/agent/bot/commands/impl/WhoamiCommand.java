package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WhoamiCommand implements CommandHandler {

    private final AuthorizationService authorizationService;
    private final SlashAccessPolicy slashAccessPolicy;


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
        boolean authorized = authorizationService.isAuthorized(event);
        return "User info:\n"
            + "  User ID: " + event.userId() + "\n"
            + "  Username: " + (event.username() != null ? "@" + event.username() : "unknown") + "\n"
            + "  Chat ID: " + event.chatId() + "\n"
            + "  Authorized: " + authorized + "\n"
            + "  Slash access: " + accessLevel;
    }
}