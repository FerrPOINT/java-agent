package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.7: /branch [name] — Fork current session. Call backend POST /api/v1/agent/session/{id}/branch.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BranchCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "branch";
    }

    @Override
    public String description() {
        return "Fork current session";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session to branch.";
        }
        String name = event.commandArgs();
        String sessionId = session.getId().toString();
        return backendClient.branchSession(sessionId, name);
    }
}