package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultPromptBuilderTest {

    @Test
    void usesConfiguredSystemPrompt() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setDefaultSystemPrompt("Hello ${agent.name}");
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        DefaultAgentConstants constants = new DefaultAgentConstants();

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, registry, constants);
        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));

        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        assertThat(msg.content()).contains("Джава агент");
    }

    @Test
    void fallsBackToDefaultPromptWhenBlank() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setDefaultSystemPrompt("  ");
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of("files", "web"));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, registry);
        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));

        assertThat(msg.content()).contains("Agent");
        assertThat(msg.content()).contains("files");
    }
}
