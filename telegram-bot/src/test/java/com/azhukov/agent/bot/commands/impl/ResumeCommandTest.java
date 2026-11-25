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

class ResumeCommandTest {

    @Test
    void listNoSessions_returnsEmpty() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.listByUserId("456")).thenReturn(List.of());
        var cmd = new ResumeCommand(store);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("No previous sessions");
    }

    @Test
    void listShowsSessions() {
        BotSessionStore store = mock(BotSessionStore.class);
        BotSessionEntity s = new BotSessionEntity();
        s.setId(UUID.randomUUID());
        s.setTitle("Test Session");
        s.setActive(true);
        when(store.listByUserId("456")).thenReturn(List.of(s));
        var cmd = new ResumeCommand(store);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Test Session");
        assertThat(result).contains("active");
    }

    @Test
    void resumeByTitle_success() {
        BotSessionStore store = mock(BotSessionStore.class);
        BotSessionEntity s = new BotSessionEntity();
        s.setId(UUID.randomUUID());
        s.setTitle("My Project");
        when(store.listByUserId("456")).thenReturn(List.of(s));
        when(store.resumeSession(s.getId(), "456")).thenReturn(s);
        var cmd = new ResumeCommand(store);
        UpdateEvent event = makeEvent("My Proj");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Resumed session");
        assertThat(result).contains("My Project");
    }

    @Test
    void nameAndDescription() {
        var cmd = new ResumeCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("resume");
        assertThat(cmd.description()).isEqualTo("Resume a previous session");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/resume " + args, null, null, null, null, null, null, true, "resume", args);
    }
}