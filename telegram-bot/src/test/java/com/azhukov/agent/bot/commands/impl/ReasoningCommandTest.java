package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReasoningCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new ReasoningCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("reasoning");
        assertThat(cmd.description()).isEqualTo("Set reasoning level (usage: /reasoning off|low|medium|high)");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new ReasoningCommand(mock(BotSessionStore.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void sessionWithNullId_returnsNoActiveSession() {
        var cmd = new ReasoningCommand(mock(BotSessionStore.class));
        BotSessionEntity session = new BotSessionEntity();
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("No active session");
    }

    @Test
    void noArgs_showsCurrentLevel() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ReasoningCommand(store);
        BotSessionEntity session = newSession(sessionId);
        session.setReasoningLevel("high");
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("high");
        assertThat(result).contains("Available:");
        assertThat(result).contains("off, low, medium, high");
    }

    @Test
    void noArgsWithNullLevel_defaultsToMedium() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ReasoningCommand(store);
        BotSessionEntity session = newSession(sessionId);
        session.setReasoningLevel(null);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("medium");
    }

    @Test
    void validLevel_setsLevel() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ReasoningCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("high"), session);
        verify(store).setReasoningLevel(sessionId, "high");
        assertThat(result).contains("high");
    }

    @Test
    void validLevelCaseInsensitive_setsLevel() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ReasoningCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("HIGH"), session);
        verify(store).setReasoningLevel(sessionId, "high");
        assertThat(result).contains("high");
    }

    @Test
    void invalidLevel_returnsErrorMessage() {
        BotSessionStore store = mock(BotSessionStore.class);
        UUID sessionId = UUID.randomUUID();
        var cmd = new ReasoningCommand(store);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("ultra"), session);
        assertThat(result).contains("Invalid level");
        assertThat(result).contains("off, low, medium, high");
        verify(store, never()).setReasoningLevel(any(), any());
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reasoning " + args, null, null, null, null, null, null, true, "reasoning", args);
    }
}