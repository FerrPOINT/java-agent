package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotMessageEntity;
import com.azhukov.agent.bot.session.BotMessageRepository;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RetryCommand implements CommandHandler {

    private final BotMessageRepository messageRepository;
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
        if (session == null || session.getId() == null) return "No active session.";
        String sessionId = session.getId().toString();
        List<BotMessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtDesc(session.getId());
        for (BotMessageEntity msg : messages) {
            if ("user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                String lastMessage = msg.getContent();
                // Remove the previous turn from backend context before retrying
                backendClient.undoTurns(sessionId, 1);
                AgentBackendClient.ChatResult result = backendClient.chat(lastMessage, sessionId);
                return result.content();
            }
        }
        return "No previous user message found to retry.";
    }
}