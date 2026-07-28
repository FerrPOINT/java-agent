package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new VoiceCommand();
        assertThat(cmd.name()).isEqualTo("voice");
        assertThat(cmd.description()).isEqualTo("Voice mode (not supported)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new VoiceCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Voice mode is not supported in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/voice", null, null, null, null, null, null, true, "voice", "");
    }
}