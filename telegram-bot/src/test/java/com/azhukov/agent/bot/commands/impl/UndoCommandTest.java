package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UndoCommandTest {

    @Test
    void undoDefaultOneTurn() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.undoTurns(sessionId.toString(), 1)).thenReturn("Undid 1 turn");
        var cmd = new UndoCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Undid 1 turn");
    }

    @Test
    void undoWithExplicitTurns() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.undoTurns(sessionId.toString(), 5)).thenReturn("Undid 5 turns");
        var cmd = new UndoCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("5");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Undid 5 turns");
    }

    @Test
    void undoInvalidNumberReturnsUsage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new UndoCommand(client);
        BotSessionEntity session = newSession(UUID.randomUUID());
        UpdateEvent event = makeEvent("abc");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Usage");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new UndoCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription() {
        var cmd = new UndoCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("undo");
        assertThat(cmd.description()).isEqualTo("Undo last N turns");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setBackendSessionId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/undo " + args, null, null, null, null, null, null, true, "undo", args);
    }
}