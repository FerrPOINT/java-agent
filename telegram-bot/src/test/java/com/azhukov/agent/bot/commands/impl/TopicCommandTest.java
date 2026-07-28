package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TopicCommandTest {

    @Test
    void handle_noArgs_returnsUsage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        var cmd = new TopicCommand(sessionStore);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("Usage");
        assertThat(result).contains("/topic");
    }

    @Test
    void handle_list_empty_returnsMessage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        var cmd = new TopicCommand(sessionStore);
        UpdateEvent event = makeEvent("list");

        String result = cmd.handle(event, null);

        assertThat(result).contains("No topic sessions");
    }

    @Test
    void handle_create_thenList() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSessionEntity newSession = new BotSessionEntity();
        newSession.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate("456", "123", "user")).thenReturn(newSession);

        var cmd = new TopicCommand(sessionStore);

        // Create a topic
        UpdateEvent createEvent = makeEvent("create test1");
        String createResult = cmd.handle(createEvent, null);
        assertThat(createResult).contains("created");

        // List topics
        UpdateEvent listEvent = makeEvent("list");
        String listResult = cmd.handle(listEvent, null);
        assertThat(listResult).contains("test1");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/topic " + args,
            null, null, null, null, null, null, true, "topic", args);
    }
}