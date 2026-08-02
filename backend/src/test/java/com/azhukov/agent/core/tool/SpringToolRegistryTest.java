package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.gateway.SendMessageTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringToolRegistryTest {

    @Test
    void sendMessageToolIncludedInDefaultToolsets() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().setDefaultToolsets(List.of("web", "file", "browser", "terminal", "coding", "memory", "skills", "core", "delegate", "gateway"));
        ManagedToolGateway managedGateway = new ManagedToolGateway(properties);

        SendMessageTool handler = mock(SendMessageTool.class);
        AgentTool annotation = SendMessageTool.class.getAnnotation(AgentTool.class);

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("sendMessageTool", handler));

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, new ObjectMapper(), managedGateway);
        registry.registerBeans();

        List<ToolDefinition> definitions = registry.getDefinitions(Set.of("gateway"));
        assertThat(definitions)
            .extracting(ToolDefinition::name)
            .contains("send_message");
    }
}
