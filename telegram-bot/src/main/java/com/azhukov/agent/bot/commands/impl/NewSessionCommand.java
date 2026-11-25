package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.core.SessionApiClient.CleanupResult;
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
        // Release runtime state without deleting the transcript; deactivation
        // binds the next message to a new backend session.
        String backendId = session.getBackendSessionId() != null
            ? session.getBackendSessionId().toString() : null;
        boolean cleanupFailed = false;
        if (backendId != null) {
            try {
                cleanupFailed = backendClient.cleanupSession(backendId) != CleanupResult.CLEANED;
            } catch (Exception e) {
                cleanupFailed = true;
            }
        }
        store.deactivateAll(session.getUserId());
        if (cleanupFailed) {
            return "Started a new session. Previous history is preserved, but runtime cleanup failed.";
        }
        return "Session context cleared. Send a message to continue.";
    }
}
