package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.10: /set_home — Set current chat as home channel. Save to BotProperties/session.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SetHomeCommand implements CommandHandler {

    private final BotProperties properties;

    

    @Override
    public String name() {
        return "set_home";
    }

    @Override
    public String description() {
        return "Set current chat as home channel";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        long chatId = event.chatId();
        properties.setHomeChatId(String.valueOf(chatId));
        return "Home channel set to chat " + chatId + ".";
    }
}