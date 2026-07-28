package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KanbanCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new KanbanCommand();
        assertThat(cmd.name()).isEqualTo("kanban");
        assertThat(cmd.description()).isEqualTo("Kanban integration (not available)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new KanbanCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Kanban integration is not available in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/kanban", null, null, null, null, null, null, true, "kanban", "");
    }
}