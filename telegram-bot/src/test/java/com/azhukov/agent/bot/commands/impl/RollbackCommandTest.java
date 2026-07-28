package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new RollbackCommand();
        assertThat(cmd.name()).isEqualTo("rollback");
        assertThat(cmd.description()).isEqualTo("Filesystem rollback (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new RollbackCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Filesystem rollback is not available. Use /undo for conversation rollback.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/rollback", null, null, null, null, null, null, true, "rollback", "");
    }
}