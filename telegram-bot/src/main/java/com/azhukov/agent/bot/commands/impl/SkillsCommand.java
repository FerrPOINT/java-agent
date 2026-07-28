package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class SkillsCommand implements CommandHandler {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "List available agent skills";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Skills list will be available here.";
    }
}