package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditsCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new CreditsCommand();
        assertThat(cmd.name()).isEqualTo("credits");
        assertThat(cmd.description()).isEqualTo("Credit balance (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new CreditsCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Credit balance is not available in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/credits", null, null, null, null, null, null, true, "credits", "");
    }
}