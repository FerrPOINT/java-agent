package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RollbackCommandTest {

    @Test
    void nameAndDescription() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new RollbackCommand(client);
        assertThat(cmd.name()).isEqualTo("rollback");
        assertThat(cmd.description()).isEqualTo("Filesystem rollback (list/restore checkpoints)");
    }

    @Test
    void listCommandCallsBackend() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.listCheckpoints()).thenReturn("Checkpoints:\n- cp1 | test | 5 files");
        var cmd = new RollbackCommand(client);
        UpdateEvent event = makeEvent("list");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Checkpoints");
        verify(client).listCheckpoints();
    }

    @Test
    void restoreCommandCallsBackend() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID id = UUID.randomUUID();
        when(client.restoreCheckpoint(id.toString())).thenReturn("Checkpoint restored: " + id);
        var cmd = new RollbackCommand(client);
        UpdateEvent event = makeEvent("restore " + id);
        String result = cmd.handle(event, null);
        assertThat(result).contains("restored");
        verify(client).restoreCheckpoint(id.toString());
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/rollback " + args,
            null, null, null, null, null, null, true, "rollback", args);
    }
}