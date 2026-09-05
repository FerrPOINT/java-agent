package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
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

class StatusCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new StatusCommand(mock(BotProperties.class), mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("status");
        assertThat(cmd.description()).isEqualTo("Show current session status");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new StatusCommand(mock(BotProperties.class), mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new StatusCommand(mock(BotProperties.class), mock(AgentBackendClient.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void showsFullStatusWithContext() throws Exception {
        BotProperties props = mock(BotProperties.class);
        when(props.getAgentName()).thenReturn("TestAgent");
        when(props.getWorkingDirectory()).thenReturn("/tmp/work");
        when(props.getDefaultModel()).thenReturn("gpt-4");

        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper om = new ObjectMapper();
        JsonNode ctx = om.readTree("{\"tokenEstimate\":6400,\"messageCount\":10}");
        UUID sessionId = UUID.randomUUID();
        when(client.getContext(sessionId.toString())).thenReturn(ctx);

        var cmd = new StatusCommand(props, client);
        BotSessionEntity session = newSession(sessionId);
        session.setModelOverride("custom-model");
        String result = cmd.handle(makeEvent(""), session);

        assertThat(result).contains("TestAgent");
        assertThat(result).contains("custom-model");
        assertThat(result).contains("/tmp/work");
        assertThat(result).contains("10%");
    }

    @Test
    void usesDefaultModelWhenOverrideBlank() {
        BotProperties props = mock(BotProperties.class);
        when(props.getAgentName()).thenReturn("Agent");
        when(props.getWorkingDirectory()).thenReturn("/wd");
        when(props.getDefaultModel()).thenReturn("default-model");

        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.getContext(sessionId.toString())).thenReturn(null);

        var cmd = new StatusCommand(props, client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("default-model");
        assertThat(result).contains("unknown");
    }

    @Test
    void usesFallbackWhenNoModelConfigured() {
        BotProperties props = mock(BotProperties.class);
        when(props.getAgentName()).thenReturn("Agent");
        when(props.getWorkingDirectory()).thenReturn("/wd");
        when(props.getDefaultModel()).thenReturn(null);

        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.getContext(sessionId.toString())).thenReturn(null);

        var cmd = new StatusCommand(props, client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("default");
    }

    @Test
    void contextFillUnknownWhenMessageCountZero() throws Exception {
        BotProperties props = mock(BotProperties.class);
        when(props.getAgentName()).thenReturn("Agent");
        when(props.getWorkingDirectory()).thenReturn("/wd");
        when(props.getDefaultModel()).thenReturn("m");

        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper om = new ObjectMapper();
        JsonNode ctx = om.readTree("{\"tokenEstimate\":100,\"messageCount\":0}");
        UUID sessionId = UUID.randomUUID();
        when(client.getContext(sessionId.toString())).thenReturn(ctx);

        var cmd = new StatusCommand(props, client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("unknown");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setBackendSessionId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/status " + args, null, null, null, null, null, null, true, "status", args);
    }
}