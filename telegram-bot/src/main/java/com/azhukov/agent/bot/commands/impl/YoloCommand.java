package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class YoloCommand implements CommandHandler {

    @Override
    public String name() {
        return "yolo";
    }

    @Override
    public String description() {
        return "Toggle YOLO mode (auto-confirm actions)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) {
            return "No active session.";
        }
        boolean newState = !session.isYoloMode();
        return "YOLO mode " + (newState ? "enabled" : "disabled") + ".";
    }
}