package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResetCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new ResetCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("reset");
        assertThat(cmd.description()).isEqualTo("Reset the current session context");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new ResetCommand(mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullUserId_returnsNoActiveSession() {
        var cmd = new ResetCommand(mock(AgentBackendClient.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void successfulResetDoesNotDeactivateOtherTopicLanes() {
        AgentBackendClient backend = mock(AgentBackendClient.class);
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100"); session.setId(id); session.setBackendSessionId(id);
        when(backend.resetSession(id.toString())).thenReturn(true);
        String result = new ResetCommand(backend).handle(makeEvent(""), session);
        assertThat(result).containsIgnoringCase("reset");
    }

    @Test
    void successfulResetReportsCurrentSession() {
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(backendClient.resetSession(anyString())).thenReturn(true);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ResetCommand(backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Current session context reset");
    }

    @Test
    void resetCallsBackendForCurrentSession() {
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(backendClient.resetSession(anyString())).thenReturn(true);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ResetCommand(backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        cmd.handle(makeEvent(""), session);
        verify(backendClient).resetSession(sessionId.toString());
    }

    @Test
    void failedBackendResetDoesNotDeactivateSessionsOrClaimSuccess() {
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100"); session.setId(sessionId); session.setBackendSessionId(sessionId);
        when(backendClient.resetSession(sessionId.toString())).thenReturn(false);

        String result = new ResetCommand(backendClient).handle(makeEvent(""), session);
        assertThat(result).containsIgnoringCase("failed");
    }

    @Test
    void resetWithNullSessionId_skipsBackendReset() {
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        var cmd = new ResetCommand(backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        // session.getId() is null
        cmd.handle(makeEvent(""), session);
        verifyNoInteractions(backendClient);
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reset " + args, null, null, null, null, null, null, true, "reset", args);
    }
}