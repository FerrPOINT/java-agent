package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetHomeCommandTest {

    @Test
    void handle_setsHomeChatId() {
        BotProperties properties = new BotProperties();
        var cmd = new SetHomeCommand(properties);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("123");
        assertThat(properties.getHomeChatId()).isEqualTo("123");
    }

    @Test
    void nameAndDescription() {
        var cmd = new SetHomeCommand(new BotProperties());
        assertThat(cmd.name()).isEqualTo("set_home");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/set_home " + args,
            null, null, null, null, null, null, true, "set_home", args);
    }
}