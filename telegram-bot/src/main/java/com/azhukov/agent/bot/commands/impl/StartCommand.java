package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class StartCommand implements CommandHandler {
    @Override
    public String name() { return "start"; }
    @Override
    public String description() { return "Initialize bot conversation"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        // Silent — Telegram protocol command, no agent reply needed
        return null;
    }
}