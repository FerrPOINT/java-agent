package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoalCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new GoalCommand();
        assertThat(cmd.name()).isEqualTo("goal");
        assertThat(cmd.description()).isEqualTo("Goal management (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new GoalCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Goal management is not available in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/goal", null, null, null, null, null, null, true, "goal", "");
    }
}