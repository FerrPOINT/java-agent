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

class CuratorCommandTest {

    private AgentBackendClient backendClient;
    private CuratorCommand cmd;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new CuratorCommand(backendClient);
        mapper = new ObjectMapper();
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("curator");
        assertThat(cmd.description()).isEqualTo("Skill maintenance: status, run, reload");
    }

    @Test
    void statusShowsSkills() {
        ArrayNode skills = mapper.createArrayNode();
        ObjectNode skill1 = mapper.createObjectNode();
        skill1.put("name", "python-dev");
        skill1.put("description", "Python development workflow");
        skills.add(skill1);
        ObjectNode skill2 = mapper.createObjectNode();
        skill2.put("name", "github");
        skill2.put("description", "GitHub operations");
        skills.add(skill2);
        when(backendClient.getSkills()).thenReturn(skills);

        String result = cmd.handle(textEvent("/curator", null), null);

        assertThat(result).contains("Total skills: 2");
        assertThat(result).contains("python-dev");
        assertThat(result).contains("github");
    }

    @Test
    void noSkillsShowsNotAvailable() {
        when(backendClient.getSkills()).thenReturn(null);

        String result = cmd.handle(textEvent("/curator", null), null);

        assertThat(result).contains("not available");
    }

    @Test
    void reloadCallsBackend() {
        when(backendClient.reloadSkills()).thenReturn("Skills reloaded successfully");

        String result = cmd.handle(textEvent("/curator", "reload"), null);

        assertThat(result).contains("reloaded");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "curator", args != null ? args : "");
    }
}