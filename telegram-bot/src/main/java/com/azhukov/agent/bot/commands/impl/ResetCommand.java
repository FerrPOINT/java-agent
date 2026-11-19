package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BackendSessionResolver;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ResetCommand implements CommandHandler {

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
        if (session == null || session.getUserId() == null || session.getId() == null) {
            return "No active session.";
        }
        String sid = BackendSessionResolver.resolveString(session);
        if (sid == null) return "No backend session yet — send a message first.";
        if (!backendClient.resetSession(sid)) {
            return "Session reset failed or could not be confirmed. No local sessions were deactivated.";
        }
        return "Current session context reset. Send a message to continue.";
    }
}