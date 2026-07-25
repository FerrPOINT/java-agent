package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultPromptBuilderTest {

    private static final String AGENT_NAME = "TestAgent";

    @Test
    void buildSystemMessage_replacesAgentNamePlaceholder_withConstantValue() {
        AgentProperties properties = new AgentProperties();
        properties.setName("IgnoredName");
        properties.getCore().setDefaultSystemPrompt("Hello, I am ${agent.name}. How can I help?");

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AgentConstants constants = new DefaultAgentConstants();
        String resolvedAgentName = constants.resolve("agent.name");

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, toolRegistry, constants);
        Message message = builder.buildSystemMessage(Session.create("user", "noop", "noop"));

        assertThat(message.role()).isEqualTo(com.azhukov.agent.core.model.Role.SYSTEM);
        assertThat(message.content()).isEqualTo("Hello, I am " + resolvedAgentName + ". How can I help?");
    }

    @Test
    void buildSystemMessage_withCustomPrompt_resolvesAgentNamePlaceholder() {
        AgentProperties properties = new AgentProperties();
        properties.setName(AGENT_NAME);
        properties.getCore().setDefaultSystemPrompt("Custom prompt for ${agent.name}. End.");

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AgentConstants constants = constantsWithAgentName(AGENT_NAME);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, toolRegistry, constants);
        Message message = builder.buildSystemMessage(Session.create("user", "noop", "noop"));

        assertThat(message.content()).isEqualTo("Custom prompt for TestAgent. End.");
    }

    @Test
    void buildSystemMessage_whenDefaultPromptBlank_generatesFallbackPromptIncludingToolsets() {
        AgentProperties properties = new AgentProperties();
        properties.setName(AGENT_NAME);
        properties.getCore().setDefaultSystemPrompt("   ");

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(new LinkedHashSet<>(Set.of("core", "web", "terminal")));

        AgentConstants constants = constantsWithAgentName(AGENT_NAME);
        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, toolRegistry, constants);
        Message message = builder.buildSystemMessage(Session.create("user", "noop", "noop"));

        assertThat(message.content())
            .startsWith("You are TestAgent.\n\nAvailable toolsets:\n")
            .contains("- core\n")
            .contains("- web\n")
            .contains("- terminal\n")
            .contains("\nRules:\n")
            .contains("1. Use tools when they help answer the user.\n")
            .contains("2. Be concise and actionable.\n")
            .contains("3. Do not invent facts; use web_search/browser when unsure.\n")
            .contains("4. For file edits use write_file/patch; for searches use search_files.\n")
            .contains("5. Dangerous terminal commands require user approval; respect the result.\n")
            .contains("6. When delegating, keep sub-tasks focused and small.\n")
            .contains("7. Prefer skills when a matching skill is available.\n")
            .contains("8. If the user asks to open a page or take a screenshot, call browser_navigate and/or browser_vision.\n");
    }

    @Test
    void buildSystemMessage_usesAgentNameFromPropertiesInFallbackPrompt() {
        AgentProperties properties = new AgentProperties();
        properties.setName("FallbackAgent");
        properties.getCore().setDefaultSystemPrompt(null);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(Set.of());

        AgentConstants constants = constantsWithAgentName("ConstantAgent");
        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, toolRegistry, constants);
        Message message = builder.buildSystemMessage(Session.create("user", "noop", "noop"));

        assertThat(message.content()).startsWith("You are FallbackAgent.\n\nAvailable toolsets:\n");
    }

    private AgentConstants constantsWithAgentName(String name) {
        return new AgentConstants() {
            @Override
            public java.util.Map<String, String> constants() {
                return java.util.Map.of("agent.name", name);
            }

            @Override
            public String resolve(String key) {
                return "agent.name".equals(key) ? name : "";
            }
        };
    }
}
