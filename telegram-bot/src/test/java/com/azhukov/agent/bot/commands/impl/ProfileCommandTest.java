package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileCommandTest {

    @Test
    void handle_showsProfileInfo() {
        BotProperties properties = new BotProperties();
        properties.setAgentName("Test Agent");
        properties.setWorkingDirectory("/test/dir");
        properties.setBackendUrl("http://test:8090");

        var cmd = new ProfileCommand(properties);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("Agent name");
        assertThat(result).contains("Working directory");
        assertThat(result).contains("Test Agent");
        assertThat(result).contains("/test/dir");
    }

    @Test
    void nameAndDescription() {
        var cmd = new ProfileCommand(new BotProperties());
        assertThat(cmd.name()).isEqualTo("profile");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/profile " + args,
            null, null, null, null, null, null, true, "profile", args);
    }
}