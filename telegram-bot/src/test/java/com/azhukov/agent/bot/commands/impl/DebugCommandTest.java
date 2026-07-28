package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCommandTest {

    @Test
    void nameAndDescription() {
        BotProperties properties = new BotProperties();
        var cmd = new DebugCommand(properties);
        assertThat(cmd.name()).isEqualTo("debug");
        assertThat(cmd.description()).isEqualTo("Show debug information");
    }

    @Test
    void handleShowsConfigSummaryAndRedactedToken() {
        BotProperties properties = new BotProperties();
        properties.setAgentName("TestDebugAgent");
        properties.setMode("polling");
        properties.setBackendUrl("http://localhost:8090");
        properties.setToken("super-secret-token-12345");

        var cmd = new DebugCommand(properties);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);

        // Contains config summary sections
        assertThat(result).contains("Configuration");
        assertThat(result).contains("Group");

        // Agent name is shown
        assertThat(result).contains("TestDebugAgent");

        // Token is redacted
        assertThat(result).contains("token: [REDACTED]");
        assertThat(result).doesNotContain("super-secret-token-12345");

        // Other key config fields
        assertThat(result).contains("mode: polling");
        assertThat(result).contains("backend-url: http://localhost:8090");

        // Section headers present
        assertThat(result).contains("== Polling ==");
        assertThat(result).contains("== Display ==");
        assertThat(result).contains("== Footer ==");
        assertThat(result).contains("== Security ==");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/debug", null, null, null, null, null, null, true, "debug", "");
    }
}