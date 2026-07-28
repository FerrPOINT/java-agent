package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotMessageEntity;
import com.azhukov.agent.bot.session.BotMessageRepository;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetryCommand implements CommandHandler {

    private final BotMessageRepository messageRepository;
    private final AgentBackendClient backendClient;

    public RetryCommand(BotMessageRepository messageRepository, AgentBackendClient backendClient) {
        this.messageRepository = messageRepository;
        this.backendClient = backendClient;
    }

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
        List<BotMessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtDesc(session.getId());
        for (BotMessageEntity msg : messages) {
            if ("user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                String lastMessage = msg.getContent();
                String response = backendClient.chat(lastMessage, session.getId().toString());
                return response;
            }
        }
        return "No previous user message found to retry.";
    }
}