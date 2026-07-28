package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FooterCommandTest {

    @Test
    void toggle_returnsEnabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.toggleFooter(any(UUID.class))).thenReturn(true);
        var cmd = new FooterCommand(store);
        BotSessionEntity session = newSession();
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("enabled");
    }

    @Test
    void toggle_returnsDisabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.toggleFooter(any(UUID.class))).thenReturn(false);
        var cmd = new FooterCommand(store);
        BotSessionEntity session = newSession();
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("disabled");
    }

    @Test
    void status_showsCurrentState() {
        BotSessionStore store = mock(BotSessionStore.class);
        var cmd = new FooterCommand(store);
        BotSessionEntity session = newSession();
        session.setFooterEnabled(true);
        UpdateEvent event = makeEvent("status");
        String result = cmd.handle(event, session);
        assertThat(result).contains("on");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        BotSessionStore store = mock(BotSessionStore.class);
        var cmd = new FooterCommand(store);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription() {
        var cmd = new FooterCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("footer");
        assertThat(cmd.description()).isEqualTo("Toggle runtime footer");
    }

    private BotSessionEntity newSession() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/footer " + args, null, null, null, null, null, null, true, "footer", args);
    }
}