package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReloadMcpCommandTest {

    @Test
    void handle_callsBackendReloadMcp() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.reloadMcp()).thenReturn("MCP servers reloaded.");

        var cmd = new ReloadMcpCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("MCP servers reloaded.");
        verify(client).reloadMcp();
    }

    @Test
    void nameAndDescription() {
        var cmd = new ReloadMcpCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("reload_mcp");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reload_mcp " + args,
            null, null, null, null, null, null, true, "reload_mcp", args);
    }
}