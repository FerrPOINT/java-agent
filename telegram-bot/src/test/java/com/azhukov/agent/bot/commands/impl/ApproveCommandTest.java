package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApproveCommandTest {

    @Test
    void defaultApprove_single() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.approve(false, null)).thenReturn("Approved");
        var cmd = new ApproveCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Approved");
    }

    @Test
    void approveAll() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.approve(true, null)).thenReturn("Approved all");
        var cmd = new ApproveCommand(client);
        UpdateEvent event = makeEvent("all");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Approved all");
    }

    @Test
    void approveSession() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.approve(false, "session")).thenReturn("Approved for session");
        var cmd = new ApproveCommand(client);
        UpdateEvent event = makeEvent("session");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Approved for session");
    }

    @Test
    void approveAlways() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.approve(false, "always")).thenReturn("Approved always");
        var cmd = new ApproveCommand(client);
        UpdateEvent event = makeEvent("always");
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Approved always");
    }

    @Test
    void approveInvalidArg_returnsUsage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new ApproveCommand(client);
        UpdateEvent event = makeEvent("xyz");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Usage");
    }

    @Test
    void nameAndDescription() {
        var cmd = new ApproveCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("approve");
        assertThat(cmd.description()).isEqualTo("Approve pending command");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/approve " + args, null, null, null, null, null, null, true, "approve", args);
    }
}