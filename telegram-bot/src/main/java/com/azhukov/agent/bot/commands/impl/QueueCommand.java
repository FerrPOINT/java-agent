package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueCommand implements CommandHandler {
    private final BusySessionHandler busyHandler;

    @Override
    public String name() { return "queue"; }
    @Override
    public String description() { return "Queue a prompt for the next turn"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String prompt = event.commandArgs();
        if (prompt == null || prompt.isBlank()) {
            return "Usage: /queue <prompt> — queues your message for the next turn without interrupting.";
        }
        // Create a synthetic TEXT event with the prompt
        UpdateEvent queuedEvent = new UpdateEvent(
            event.updateId(), UpdateEvent.Type.TEXT, event.chatId(), event.userId(),
            event.username(), prompt, null, null, null, null, null, null,
            false, null, null, event.messageId(), null, 0, event.forwardedFrom());
        busyHandler.queueMessage(event.chatId(), queuedEvent);
        return "📋 Queued for next turn: " + prompt;
    }
}