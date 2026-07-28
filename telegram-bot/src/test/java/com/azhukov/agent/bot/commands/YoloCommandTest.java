package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.YoloCommand;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YoloCommandTest {

    @Test
    void toggleOffToOn_returnsEnabled() {
        var cmd = new YoloCommand();
        BotSessionEntity session = new BotSessionEntity();
        session.setYoloMode(false);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, session);
        assertThat(result).contains("enabled");
    }

    @Test
    void toggleOnToOff_returnsDisabled() {
        var cmd = new YoloCommand();
        BotSessionEntity session = new BotSessionEntity();
        session.setYoloMode(true);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, session);
        assertThat(result).contains("disabled");
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new YoloCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription_correct() {
        var cmd = new YoloCommand();
        assertThat(cmd.name()).isEqualTo("yolo");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/yolo", null, null, null, null, null, null, true, "yolo", "");
    }
}