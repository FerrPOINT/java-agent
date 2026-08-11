package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResetCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new ResetCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("reset");
        assertThat(cmd.description()).isEqualTo("Reset the current session context");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new ResetCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullUserId_returnsNoActiveSession() {
        var cmd = new ResetCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void resetDeactivatesAndReturnsCount() {
        BotSessionStore store = mock(BotSessionStore.class);
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(store.deactivateAll("100")).thenReturn(3);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ResetCommand(store, backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setId(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("3");
        assertThat(result).contains("deactivated");
        verify(store).deactivateAll("100");
    }

    @Test
    void resetCallsBackendResetSessionBeforeDeactivating() {
        BotSessionStore store = mock(BotSessionStore.class);
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(backendClient.resetSession(anyString())).thenReturn(true);
        when(store.deactivateAll("100")).thenReturn(2);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ResetCommand(store, backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setId(sessionId);
        cmd.handle(makeEvent(""), session);
        // Verify backend context was reset before sessions were deactivated
        verify(backendClient).resetSession(sessionId.toString());
        verify(store).deactivateAll("100");
    }

    @Test
    void resetWithNullSessionId_skipsBackendReset() {
        BotSessionStore store = mock(BotSessionStore.class);
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(store.deactivateAll("100")).thenReturn(1);
        var cmd = new ResetCommand(store, backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        // session.getId() is null
        cmd.handle(makeEvent(""), session);
        verifyNoInteractions(backendClient);
        verify(store).deactivateAll("100");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reset " + args, null, null, null, null, null, null, true, "reset", args);
    }
}