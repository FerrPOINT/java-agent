package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VerboseCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new VerboseCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("verbose");
        assertThat(cmd.description()).isEqualTo("Toggle verbose output mode");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new VerboseCommand(mock(BotSessionStore.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new VerboseCommand(mock(BotSessionStore.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void toggleOn_returnsEnabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        when(store.toggleVerbose(sessionId)).thenReturn(true);
        var cmd = new VerboseCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("enabled");
        verify(store).toggleVerbose(sessionId);
    }

    @Test
    void toggleOff_returnsDisabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        when(store.toggleVerbose(sessionId)).thenReturn(false);
        var cmd = new VerboseCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("disabled");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/verbose " + args, null, null, null, null, null, null, true, "verbose", args);
    }
}