package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new ReloadCommand();
        assertThat(cmd.name()).isEqualTo("reload");
        assertThat(cmd.description()).isEqualTo("Reload .env variables into running session");
    }

    @Test
    void handleReturnsRestartMessage() {
        var cmd = new ReloadCommand();
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("/restart");
        assertThat(result).contains(".env");
    }

    @Test
    void handleWithSessionStillReturnsMessage() {
        var cmd = new ReloadCommand();
        BotSessionEntity session = new BotSessionEntity();
        UpdateEvent event = makeEvent("anything");
        String result = cmd.handle(event, session);
        assertThat(result).contains("/restart");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reload " + args, null, null, null, null, null, null, true, "reload", args);
    }
}