package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RestartCommandTest {

    @Test
    void handle_callsBackendRestart() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.restart()).thenReturn("Agent restarting...");

        var cmd = new RestartCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("Agent restarting...");
        verify(client).restart();
    }

    @Test
    void nameAndDescription() {
        var cmd = new RestartCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("restart");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/restart " + args,
            null, null, null, null, null, null, true, "restart", args);
    }
}