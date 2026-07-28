package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DenyCommandTest {

    @Test
    void denySingle() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.deny(false)).thenReturn("Denied");
        var cmd = new DenyCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Denied");
    }

    @Test
    void denyAll() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.deny(true)).thenReturn("Denied all");
        var cmd = new DenyCommand(client);
        UpdateEvent event = makeEvent("all");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Denied all");
    }

    @Test
    void nameAndDescription() {
        var cmd = new DenyCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("deny");
        assertThat(cmd.description()).isEqualTo("Deny pending command");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/deny " + args, null, null, null, null, null, null, true, "deny", args);
    }
}