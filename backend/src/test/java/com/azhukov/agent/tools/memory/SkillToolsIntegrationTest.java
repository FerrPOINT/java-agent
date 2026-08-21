package com.azhukov.agent.tools.memory;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.core.tool.SpringToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
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
@Tag("slow")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "agent.model.provider=noop",
    "agent.skills.enabled=true"
})
class SkillToolsIntegrationTest extends PostgresTestContainer {

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
