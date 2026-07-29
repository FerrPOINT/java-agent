package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KanbanCommandTest {

    private AgentBackendClient backendClient;
    private KanbanCommand cmd;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new KanbanCommand(backendClient);
        mapper = new ObjectMapper();
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("kanban");
        assertThat(cmd.description()).isEqualTo("Show active agents and tasks");
    }

    @Test
    void emptyBoardShowsMessage() {
        when(backendClient.listActiveAgents()).thenReturn(mapper.createArrayNode());

        String result = cmd.handle(textEvent("/kanban", null), null);

        assertThat(result).contains("No active agents");
    }

    @Test
    void showsActiveAgents() {
        ArrayNode agents = mapper.createArrayNode();
        ObjectNode agent = mapper.createObjectNode();
        agent.put("id", "abc-123");
        agent.put("status", "running");
        agent.put("prompt", "Fix tests and run them");
        agents.add(agent);
        when(backendClient.listActiveAgents()).thenReturn(agents);

        String result = cmd.handle(textEvent("/kanban", null), null);

        assertThat(result).contains("running");
        assertThat(result).contains("Fix tests");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "kanban", args != null ? args : "");
    }
}