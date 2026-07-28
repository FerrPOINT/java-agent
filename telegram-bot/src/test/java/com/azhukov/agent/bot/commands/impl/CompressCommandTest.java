package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompressCommandTest {

    @Test
    void compressWithFocus() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.compressSession(sessionId.toString(), "summarize")).thenReturn("Compressed");
        var cmd = new CompressCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("summarize");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Compressed");
    }

    @Test
    void compressWithoutFocus() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.compressSession(eq(sessionId.toString()), any())).thenReturn("Done");
        var cmd = new CompressCommand(client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Done");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new CompressCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription() {
        var cmd = new CompressCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("compress");
        assertThat(cmd.description()).isEqualTo("Compress session context");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/compress " + args, null, null, null, null, null, null, true, "compress", args);
    }
}