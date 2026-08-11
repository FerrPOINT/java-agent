package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class StartCommand implements CommandHandler {

    private static final String WELCOME_MESSAGE =
        "Привет! Я — автономный ИИ-агент. Помогаю с кодом, файлами, поиском и задачами. "
            + "Напиши /help чтобы увидеть все команды.";

    @Override
    public String name() { return "start"; }
    @Override
    public String description() { return "Initialize bot conversation"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return WELCOME_MESSAGE;
    }
}