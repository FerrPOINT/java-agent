package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class StatusCommand implements CommandHandler {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show current session status";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) {
            return "No active session.";
        }
        return "Session status:\n"
            + "  Model: " + (session.getModelOverride() != null ? session.getModelOverride() : "default") + "\n"
            + "  YOLO: " + session.isYoloMode() + "\n"
            + "  Verbose: " + session.isVerboseMode() + "\n"
            + "  Fast: " + session.isFastMode() + "\n"
            + "  Reasoning: " + session.getReasoningLevel();
    }
}