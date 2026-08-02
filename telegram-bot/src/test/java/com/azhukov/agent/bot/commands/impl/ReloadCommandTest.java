package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReloadCommandTest {

    private AgentBackendClient backendClient;
    private ReloadCommand cmd;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new ReloadCommand(backendClient);
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("reload");
        assertThat(cmd.description()).isEqualTo("Reload skills and MCP servers");
    }

    @Test
    void handleCallsReloadAll() {
        when(backendClient.reloadAll()).thenReturn("Skills and MCP servers reloaded.");
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("reloaded");
        verify(backendClient).reloadAll();
    }

    @Test
    void handleWithSessionStillCallsReloadAll() {
        when(backendClient.reloadAll()).thenReturn("Skills and MCP servers reloaded.");
        BotSessionEntity session = new BotSessionEntity();
        UpdateEvent event = makeEvent("anything");
        String result = cmd.handle(event, session);
        assertThat(result).contains("reloaded");
        verify(backendClient).reloadAll();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reload " + args, null, null, null, null, null, null, true, "reload", args);
    }
}