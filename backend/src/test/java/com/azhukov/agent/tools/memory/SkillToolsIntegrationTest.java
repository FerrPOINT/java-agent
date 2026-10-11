package com.azhukov.agent.tools.memory;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.core.tool.SpringToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.persistence.PostgresActiveServicesTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@Tag("slow")
class SkillToolsIntegrationTest extends PostgresActiveServicesTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SpringToolRegistry toolRegistry;

    @Test
    void skillToolsAreRegistered() {
        var definitions = toolRegistry.getDefinitions(null);
        assertThat(definitions).anyMatch(d -> "skill_manage".equals(d.name()));
        assertThat(definitions).anyMatch(d -> "skills_list".equals(d.name()));
        assertThat(definitions).anyMatch(d -> "clarify".equals(d.name()));
    }
}
