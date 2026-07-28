package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nameAndDescription() {
        var cmd = new MemoryCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("memory");
        assertThat(cmd.description()).contains("Manage memory");
    }

    @Test
    void listAllMemory_empty() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.listAllMemory("default")).thenReturn(mapper.createArrayNode());
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent(null), null);
        assertThat(result).contains("No memory facts");
    }

    @Test
    void listAllMemory_withEntries() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ArrayNode arr = mapper.createArrayNode();
        var entry = mapper.createObjectNode();
        entry.put("target", "memory");
        entry.put("fact", "User prefers dark mode");
        entry.put("category", "preference");
        entry.put("id", "abcdef1234");
        arr.add(entry);
        when(client.listAllMemory("default")).thenReturn(arr);
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent(null), null);
        assertThat(result).contains("User prefers dark mode");
        assertThat(result).contains("[memory]");
    }

    @Test
    void pending_empty() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.listPendingMemory("default")).thenReturn(mapper.createArrayNode());
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent("pending"), null);
        assertThat(result).contains("No pending");
    }

    @Test
    void pending_withEntries() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ArrayNode arr = mapper.createArrayNode();
        var entry = mapper.createObjectNode();
        entry.put("action", "add");
        entry.put("target", "memory");
        entry.put("summary", "test summary");
        entry.put("id", "abcdef1234");
        arr.add(entry);
        when(client.listPendingMemory("default")).thenReturn(arr);
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent("pending"), null);
        assertThat(result).contains("add");
        assertThat(result).contains("test summary");
    }

    @Test
    void approve_success() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.approvePendingMemory("default", "some-id")).thenReturn(true);
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent("approve some-id"), null);
        assertThat(result).contains("Approved");
    }

    @Test
    void reject_success() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.rejectPendingMemory("default", "some-id")).thenReturn(true);
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent("reject some-id"), null);
        assertThat(result).contains("Rejected");
    }

    @Test
    void approvalOn() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        var cmd = new MemoryCommand(client);
        String result = cmd.handle(makeEvent("approval on"), null);
        assertThat(result).contains("ON");
        verify(client).setMemoryApproval(true);
    }

    private UpdateEvent makeEvent(String args) {
        String text = args != null ? "/memory " + args : "/memory";
        String commandArgs = args != null ? args : "";
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null,
            null, null, null, true, "memory", commandArgs);
    }
}