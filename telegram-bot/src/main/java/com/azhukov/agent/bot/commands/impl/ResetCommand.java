package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ResetCommand implements CommandHandler {

    private final BotSessionStore store;
    private final AgentBackendClient backendClient;


    @Override
    public String name() {
        return "reset";
    }

    @Override
    public String description() {
        return "Reset the current session context";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getUserId() == null) {
            return "No active session.";
        }
        if (session.getId() != null) {
            backendClient.resetSession(session.getId().toString());
        }
        int count = store.deactivateAll(session.getUserId());
        return "All sessions reset (" + count + " deactivated). Send a message to start fresh.";
    }
}