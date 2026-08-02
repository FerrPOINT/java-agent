package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCommandTest {

    @Test
    void handle_showsBotStatus() {
        BotProperties props = new BotProperties();
        var cmd = new PlatformCommand(props);
        UpdateEvent event = makeEvent(null);

        String result = cmd.handle(event, null);

        assertThat(result).contains("Mode:");
        assertThat(result).contains("polling");
    }

    @Test
    void handle_showsWebhookInfo() {
        BotProperties props = new BotProperties();
        props.setMode("webhook");
        props.getWebhook().setUrl("https://example.com/webhook");
        var cmd = new PlatformCommand(props);
        UpdateEvent event = makeEvent(null);

        String result = cmd.handle(event, null);

        assertThat(result).contains("Mode: webhook");
        assertThat(result).contains("Webhook URL: https://example.com/webhook");
    }

    @Test
    void nameAndDescription() {
        var cmd = new PlatformCommand(new BotProperties());
        assertThat(cmd.name()).isEqualTo("platform");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/platform" + (args != null ? " " + args : ""),
            null, null, null, null, null, null, true, "platform", args != null ? args : "");
    }
}