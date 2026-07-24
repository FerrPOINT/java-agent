package com.azhukov.agent.tools.memory;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.core.tool.SpringToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "agent.model.provider=noop",
    "agent.skills.enabled=true"
})
class SkillToolsIntegrationTest {

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
