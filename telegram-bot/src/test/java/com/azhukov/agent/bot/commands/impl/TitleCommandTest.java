package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TitleCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new TitleCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("title");
        assertThat(cmd.description()).isEqualTo("Set or show the session title (usage: /title [text])");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new TitleCommand(mock(BotSessionStore.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new TitleCommand(mock(BotSessionStore.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void noArgsWithExistingTitle_showsCurrentTitle() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new TitleCommand(store);
        BotSessionEntity session = newSession(sessionId);
        session.setTitle("My Session");
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("My Session");
        assertThat(result).contains("Current title");
    }

    @Test
    void noArgsWithNullTitle_showsNoTitleSet() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new TitleCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No title set");
    }

    @Test
    void withArgs_setsTitle() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new TitleCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("New Title"), session);
        verify(store).updateTitle(sessionId, "New Title");
        assertThat(result).contains("New Title");
        assertThat(result).contains("Title set to");
    }

    @Test
    void withBlankArgs_showsCurrentTitle() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new TitleCommand(store);
        BotSessionEntity session = newSession(sessionId);
        session.setTitle("Existing");
        String result = cmd.handle(makeEvent("  "), session);
        assertThat(result).contains("Existing");
        verify(store, never()).updateTitle(any(), any());
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/title " + args, null, null, null, null, null, null, true, "title", args);
    }
}