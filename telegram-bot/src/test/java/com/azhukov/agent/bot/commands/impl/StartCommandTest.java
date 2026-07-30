package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartCommandTest {

    @Test
    void startCommandMetadataAndHandleBehavior() {
        var cmd = new StartCommand();

        // Verify name and description
        assertThat(cmd.name()).isEqualTo("start");
        assertThat(cmd.description()).isEqualTo("Initialize bot conversation");

        // handle() returns null with no session (no-op command — Telegram protocol only)
        UpdateEvent eventNoArgs = makeEvent("");
        assertThat(cmd.handle(eventNoArgs, null)).isNull();

        // handle() returns null even with a session and args — it is a true no-op
        BotSessionEntity session = new BotSessionEntity();
        UpdateEvent eventWithArgs = makeEvent("some args");
        assertThat(cmd.handle(eventWithArgs, session)).isNull();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/start " + args, null, null, null, null, null, null, true, "start", args);
    }
}