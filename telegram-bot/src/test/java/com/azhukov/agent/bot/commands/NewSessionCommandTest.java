package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.NewSessionCommand;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewSessionCommandTest {

    @Test
    void name_isNew() {
        var cmd = new NewSessionCommand();
        assertThat(cmd.name()).isEqualTo("new");
    }

    @Test
    void description_isNotBlank() {
        var cmd = new NewSessionCommand();
        assertThat(cmd.description()).isNotBlank();
    }

    @Test
    void handle_returnsStartMessage() {
        var cmd = new NewSessionCommand();
        UpdateEvent event = new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/new", null, null, null, null, null, null, true, "new", "");
        String result = cmd.handle(event, new BotSessionEntity());
        assertThat(result).contains("New session");
    }
}