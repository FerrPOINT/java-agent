package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubgoalCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new SubgoalCommand();
        assertThat(cmd.name()).isEqualTo("subgoal");
        assertThat(cmd.description()).isEqualTo("Subgoal management (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new SubgoalCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Subgoal management is not available in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/subgoal", null, null, null, null, null, null, true, "subgoal", "");
    }
}