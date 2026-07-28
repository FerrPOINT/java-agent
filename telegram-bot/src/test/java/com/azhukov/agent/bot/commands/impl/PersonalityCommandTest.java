package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalityCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new PersonalityCommand();
        assertThat(cmd.name()).isEqualTo("personality");
        assertThat(cmd.description()).isEqualTo("Personality system (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new PersonalityCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Personality system is not available. Configure agent.name in application.yml.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/personality", null, null, null, null, null, null, true, "personality", "");
    }
}