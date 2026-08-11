package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartCommandTest {

    @Test
    void startCommandMetadata() {
        var cmd = new StartCommand();
        assertThat(cmd.name()).isEqualTo("start");
        assertThat(cmd.description()).isEqualTo("Initialize bot conversation");
    }

    @Test
    void startCommandReturnsWelcomeMessageWithoutSession() {
        var cmd = new StartCommand();
        UpdateEvent event = makeEvent("");
        String response = cmd.handle(event, null);
        assertThat(response).isNotNull();
        assertThat(response).contains("Привет!");
        assertThat(response).contains("/help");
    }

    @Test
    void startCommandReturnsWelcomeMessageWithSession() {
        var cmd = new StartCommand();
        BotSessionEntity session = new BotSessionEntity();
        UpdateEvent event = makeEvent("some args");
        String response = cmd.handle(event, session);
        assertThat(response).isNotNull();
        assertThat(response).contains("автономный ИИ-агент");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/start " + args, null, null, null, null, null, null, true, "start", args);
    }
}