package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionsCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new SessionsCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("sessions");
        assertThat(cmd.description()).isEqualTo("List your chat sessions");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new SessionsCommand(mock(BotSessionStore.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullUserId_returnsNoActiveSession() {
        var cmd = new SessionsCommand(mock(BotSessionStore.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void emptyList_returnsNoSessionsFound() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.listByUserId("100")).thenReturn(List.of());
        var cmd = new SessionsCommand(store);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No sessions found");
    }

    @Test
    void nullList_returnsNoSessionsFound() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.listByUserId("100")).thenReturn(null);
        var cmd = new SessionsCommand(store);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No sessions found");
    }

    @Test
    void listShowsSessions() {
        BotSessionStore store = mock(BotSessionStore.class);
        BotSessionEntity s1 = newSession(UUID.randomUUID(), "Project A", true);
        BotSessionEntity s2 = newSession(UUID.randomUUID(), null, false);
        when(store.listByUserId("100")).thenReturn(List.of(s1, s2));
        var cmd = new SessionsCommand(store);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Project A");
        assertThat(result).contains("[active]");
        assertThat(result).contains("Untitled");
    }

    private BotSessionEntity newSession(UUID id, String title, boolean active) {
        BotSessionEntity s = new BotSessionEntity();
        s.setId(id);
        s.setTitle(title);
        s.setActive(active);
        return s;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/sessions " + args, null, null, null, null, null, null, true, "sessions", args);
    }
}