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

class AgentsCommandTest {

    @Test
    void noActiveAgents_returnsEmpty() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.listActiveAgents()).thenReturn(null);
        var cmd = new AgentsCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("No active agents");
    }

    @Test
    void listsActiveAgents() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arr = mapper.createArrayNode();
        arr.add(mapper.createObjectNode().put("sessionId", "abc-123").put("status", "running"));
        arr.add(mapper.createObjectNode().put("sessionId", "def-456").put("status", "idle"));
        when(client.listActiveAgents()).thenReturn(arr);
        var cmd = new AgentsCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Active agents (2)");
        assertThat(result).contains("abc-123");
        assertThat(result).contains("running");
        assertThat(result).contains("def-456");
        assertThat(result).contains("idle");
    }

    @Test
    void emptyArray_returnsNoActiveAgents() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(client.listActiveAgents()).thenReturn(mapper.createArrayNode());
        var cmd = new AgentsCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("No active agents");
    }

    @Test
    void nameAndDescription() {
        var cmd = new AgentsCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("agents");
        assertThat(cmd.description()).isEqualTo("List active agents");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/agents " + args, null, null, null, null, null, null, true, "agents", args);
    }
}