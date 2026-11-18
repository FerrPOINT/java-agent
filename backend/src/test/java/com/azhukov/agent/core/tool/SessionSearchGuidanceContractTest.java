package com.azhukov.agent.core.tool;

import com.azhukov.agent.tools.memory.SessionSearchTool;
import com.azhukov.agent.tools.memory.SessionSearchService;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.SpringToolRegistry;
import com.azhukov.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionSearchGuidanceContractTest {
    @Test
    void registeredSchemaExplainsBrowseReadAndUuidAnchor() {
        ApplicationContext context = mock(ApplicationContext.class);
        SessionSearchTool handler = new SessionSearchTool(mock(SessionSearchService.class), new ObjectMapper());
        when(context.getBeansWithAnnotation(AgentTool.class)).thenReturn(Map.of("sessionSearchTool", handler));
        AgentProperties config = new AgentProperties();
        SpringToolRegistry registry = new SpringToolRegistry(context, config, new ObjectMapper(),
            new com.azhukov.agent.core.tool.ManagedToolGate(config));
        registry.registerBeans();
        ToolDefinition definition = registry.getDefinitions().stream()
            .filter(d -> d.name().equals("session_search")).findFirst().orElseThrow();
        assertThat(definition.description()).contains("browse recent sessions first", "last messages")
            .doesNotContain("around_message_id=12345");
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        Map<?, ?> session = (Map<?, ?>) properties.get("session_id");
        Map<?, ?> anchor = (Map<?, ?>) properties.get("around_message_id");
        assertThat(session.get("description").toString()).contains("Pass alone");
        assertThat(anchor.get("description").toString()).contains("UUID", "not its position");
    }
}
