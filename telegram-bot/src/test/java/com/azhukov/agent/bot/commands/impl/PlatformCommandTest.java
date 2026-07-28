package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCommandTest {

    @Test
    void handle_list_showsTelegram() {
        var cmd = new PlatformCommand(new BotProperties());
        UpdateEvent event = makeEvent("list");

        String result = cmd.handle(event, null);

        assertThat(result).contains("telegram");
        assertThat(result).contains("active");
    }

    @Test
    void nameAndDescription() {
        var cmd = new PlatformCommand(new BotProperties());
        assertThat(cmd.name()).isEqualTo("platform");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/platform " + args,
            null, null, null, null, null, null, true, "platform", args);
    }
}