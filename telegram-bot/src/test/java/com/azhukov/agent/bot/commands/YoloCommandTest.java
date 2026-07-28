package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.YoloCommand;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoloCommandTest {

    @Test
    void toggleOffToOn_returnsEnabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.toggleYolo(any(UUID.class))).thenReturn(true);
        var cmd = new YoloCommand(store);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setYoloMode(false);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, session);
        assertThat(result).contains("enabled");
    }

    @Test
    void toggleOnToOff_returnsDisabled() {
        BotSessionStore store = mock(BotSessionStore.class);
        when(store.toggleYolo(any(UUID.class))).thenReturn(false);
        var cmd = new YoloCommand(store);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setYoloMode(true);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, session);
        assertThat(result).contains("disabled");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new YoloCommand(mock(BotSessionStore.class));
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription_correct() {
        var cmd = new YoloCommand(mock(BotSessionStore.class));
        assertThat(cmd.name()).isEqualTo("yolo");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/yolo", null, null, null, null, null, null, true, "yolo", "");
    }
}