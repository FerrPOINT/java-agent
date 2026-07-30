package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class YoloCommand implements CommandHandler {

    private final BotSessionStore store;

    

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
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        boolean newState = store.toggleYolo(session.getId());
        return "YOLO mode " + (newState ? "enabled" : "disabled") + ".";
    }
}