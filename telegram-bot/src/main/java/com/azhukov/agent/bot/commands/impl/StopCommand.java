package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StopCommand implements CommandHandler {

    private final BusySessionHandler busyHandler;
    private final com.azhukov.agent.bot.core.AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the current generation";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        busyHandler.interrupt(event.chatId());
        // Hermes parity: /stop cancels the ACTIVE BACKEND TURN, not just the
        // local stream rendering. Best-effort — a missing backend session
        // still interrupts local delivery.
        String backendSessionId = session != null && session.getBackendSessionId() != null
            ? session.getBackendSessionId().toString() : null;
        boolean backendStopped = backendSessionId != null && backendClient.stop(backendSessionId);
        return backendStopped
            ? "Stopping current generation..."
            : "Stopped local stream (no active backend turn).";
    }
}