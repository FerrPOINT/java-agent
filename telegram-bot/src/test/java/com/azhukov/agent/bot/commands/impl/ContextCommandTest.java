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

class ContextCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new ContextCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("context");
        assertThat(cmd.description()).isEqualTo("Show current context size and details");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new ContextCommand(mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new ContextCommand(mock(AgentBackendClient.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void nullNode_returnsFailedMessage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.getContext(sessionId.toString())).thenReturn(null);
        var cmd = new ContextCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Failed to retrieve context");
    }

    @Test
    void validNode_showsContextInfo() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        ObjectMapper om = new ObjectMapper();
        JsonNode node = om.readTree("{\"messageCount\":15,\"tokenEstimate\":8000,\"toolsUsed\":\"shell,editor\"}");
        when(client.getContext(sessionId.toString())).thenReturn(node);
        var cmd = new ContextCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Context info:");
        assertThat(result).contains("15");
        assertThat(result).contains("8000");
        assertThat(result).contains("shell,editor");
    }

    @Test
    void validNodeWithDefaults_showsDefaultToolsUsed() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        ObjectMapper om = new ObjectMapper();
        JsonNode node = om.readTree("{\"messageCount\":5,\"tokenEstimate\":1000}");
        when(client.getContext(sessionId.toString())).thenReturn(node);
        var cmd = new ContextCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("none");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/context " + args, null, null, null, null, null, null, true, "context", args);
    }
}