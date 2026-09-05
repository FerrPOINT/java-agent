package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StopCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new StopCommand(mock(BusySessionHandler.class), mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("stop");
        assertThat(cmd.description()).isEqualTo("Stop the current generation");
    }

    @Test
    void handleCancelsBackendTurnAndInterruptsLocally() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        AgentBackendClient backend = mock(AgentBackendClient.class);
        UUID backendId = UUID.randomUUID();
        when(backend.stop(backendId.toString())).thenReturn(true);
        var cmd = new StopCommand(handler, backend);

        BotSessionEntity session = new BotSessionEntity();
        session.setBackendSessionId(backendId);

        String result = cmd.handle(makeEvent(""), session);
        verify(handler).interrupt(123L);
        verify(backend).stop(backendId.toString());
        assertThat(result).isEqualTo("Stopping current generation...");
    }

    @Test
    void noBackendSessionStillInterruptsLocalStream() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        AgentBackendClient backend = mock(AgentBackendClient.class);
        var cmd = new StopCommand(handler, backend);

        String result = cmd.handle(makeEvent("ignored"), null);
        verify(handler).interrupt(123L);
        verify(backend, never()).stop(any());
        assertThat(result).contains("Stopped local stream");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/stop " + args, null, null, null, null, null, null, true, "stop", args);
    }
}
