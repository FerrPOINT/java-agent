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

class SkillsCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new SkillsCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("skills");
        assertThat(cmd.description()).isEqualTo("List available agent skills");
    }

    @Test
    void nullNode_returnsNoSkills() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.getSkills()).thenReturn(null);
        var cmd = new SkillsCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).isEqualTo("No skills available.");
    }

    @Test
    void emptyArray_returnsNoSkills() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper om = new ObjectMapper();
        ArrayNode emptyArray = om.createArrayNode();
        when(client.getSkills()).thenReturn(emptyArray);
        var cmd = new SkillsCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).isEqualTo("No skills available.");
    }

    @Test
    void nonArrayNode_returnsNoSkills() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper om = new ObjectMapper();
        JsonNode objNode = om.readTree("{\"key\":\"value\"}");
        when(client.getSkills()).thenReturn(objNode);
        var cmd = new SkillsCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).isEqualTo("No skills available.");
    }

    @Test
    void populatedArray_listsSkills() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper om = new ObjectMapper();
        ArrayNode arr = om.createArrayNode();
        arr.add("coding");
        arr.add("research");
        when(client.getSkills()).thenReturn(arr);
        var cmd = new SkillsCommand(client);
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("Available skills:");
        assertThat(result).contains("coding");
        assertThat(result).contains("research");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/skills " + args, null, null, null, null, null, null, true, "skills", args);
    }
}