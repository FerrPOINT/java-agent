package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CronCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new CronCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("cron");
        assertThat(cmd.description()).isEqualTo("Manage scheduled tasks (list, add, pause, resume, remove)");
    }

    @Test
    void emptyArgs_returnsUsage() {
        var cmd = new CronCommand(mock(AgentBackendClient.class));
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Usage: /cron");
        assertThat(result).contains("list, add, pause");
    }

    @Test
    void nullArgs_returnsUsage() {
        var cmd = new CronCommand(mock(AgentBackendClient.class));
        UpdateEvent event = makeEvent(null);
        String result = cmd.handle(event, null);
        assertThat(result).contains("Usage: /cron");
    }

    @Test
    void validArgs_returnsBackendResponse() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(anyString(), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("Cron job added"));
        var cmd = new CronCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("add 0 6 * * * backup");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Cron job added");
    }

    @Test
    void backendThrows_returnsErrorMessage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(anyString(), eq(sessionId.toString()))).thenThrow(new RuntimeException("Backend down"));
        var cmd = new CronCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("list");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Error managing cron jobs");
        assertThat(result).contains("Backend down");
    }

    @Test
    void backendReturnsErrorContent_passesThrough() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(anyString(), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("Error: backend unavailable"));
        var cmd = new CronCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("list");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Error: backend unavailable");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setBackendSessionId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/cron " + (args != null ? args : ""), null, null, null, null, null, null, true, "cron", args);
    }
}