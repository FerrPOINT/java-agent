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
public class NewSessionCommand implements CommandHandler {

    private final BotSessionStore store;
    private final AgentBackendClient backendClient;


    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Start a new chat session";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        // Reset the BACKEND conversation when one exists, then deactivate this
        // bot session so the next message binds to a brand-new backend session.
        // Previously this reset the local bot row id (a nonexistent backend id),
        // so the old backendSessionId stayed bound and history leaked across /new.
        String backendId = session.getBackendSessionId() != null
            ? session.getBackendSessionId().toString() : null;
        if (backendId != null) {
            try {
                backendClient.resetSession(backendId);
            } catch (Exception e) {
                // Backend cleanup is best-effort; the local rotation below still
                // guarantees a fresh session on the next turn.
            }
        }
        store.deactivateAll(session.getUserId());
        return "Session context cleared. Send a message to continue.";
    }
}