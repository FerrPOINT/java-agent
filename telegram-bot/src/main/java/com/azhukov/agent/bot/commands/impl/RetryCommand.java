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
public class RetryCommand implements CommandHandler {

    private final AgentBackendClient backendClient;


    @Override
    public String name() {
        return "retry";
    }

    @Override
    public String description() {
        return "Retry last message";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) return "No active session.";
        String sessionId = BackendSessionResolver.resolveString(session);
        if (sessionId == null) return "No backend session yet — send a message first.";
        // Read the last USER message from the live backend transcript
        // (the local bot_messages table has no producer — Hermes parity is to
        // rewind the persisted transcript and resubmit the prior real turn).
        com.fasterxml.jackson.databind.JsonNode messages = backendClient.getMessages(sessionId, 50);
        if (messages != null && messages.isArray()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                com.fasterxml.jackson.databind.JsonNode msg = messages.get(i);
                if ("user".equalsIgnoreCase(msg.path("role").asText())
                        && !msg.path("content").asText("").isBlank()) {
                    String lastMessage = msg.path("content").asText();
                    // Rewind the previous turn, then resubmit it
                    backendClient.undoTurns(sessionId, 1);
                    AgentBackendClient.ChatResult result = backendClient.chat(lastMessage, sessionId);
                    return result.content();
                }
            }
        }
        return "No previous user message found to retry.";
    }
}