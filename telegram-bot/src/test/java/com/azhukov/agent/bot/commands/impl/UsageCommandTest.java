package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UsageCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new UsageCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("usage");
        assertThat(cmd.description()).isEqualTo("Show token usage statistics");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new UsageCommand(mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new UsageCommand(mock(AgentBackendClient.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void nullNode_returnsFailedMessage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.getUsage(sessionId.toString())).thenReturn(null);
        var cmd = new UsageCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Failed to retrieve usage");
    }

    @Test
    void validNode_showsUsage() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        ObjectMapper om = new ObjectMapper();
        JsonNode node = om.readTree("{\"messageCount\":42,\"tokenEstimate\":15000}");
        when(client.getUsage(sessionId.toString())).thenReturn(node);
        var cmd = new UsageCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("42");
        assertThat(result).contains("15000");
        assertThat(result).contains("Usage:");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setBackendSessionId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/usage " + args, null, null, null, null, null, null, true, "usage", args);
    }
}