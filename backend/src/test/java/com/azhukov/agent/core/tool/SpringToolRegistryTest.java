package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringToolRegistryTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MSG = Message.user("test");

    @Mock
    private ApplicationContext context;
    @Mock
    private ManagedToolGateway managedToolGateway;

    private AgentProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should register bean annotated with @AgentTool and list its definition")
    void shouldRegisterAnnotatedBean() {
        // Use a real annotated handler so registerBeans() picks it up
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(true);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        List<ToolDefinition> defs = registry.getDefinitions();
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("test_tool");
        assertThat(defs.get(0).description()).isNotBlank();
    }

    @Test
    @DisplayName("Should execute registered tool and return its result")
    void shouldExecuteRegisteredTool() {
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(true);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        ToolResult result = registry.execute("test_tool", "call-1", "{}", LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("test-ok");
    }

    @Test
    @DisplayName("Should return fail for unknown tool")
    void shouldReturnFailForUnknownTool() {
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of());

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        ToolResult result = registry.execute("nonexistent", "call-2", "{}", LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
        assertThat(result.error()).contains("nonexistent");
    }

    @Test
    @DisplayName("Should filter definitions by toolset")
    void shouldFilterDefinitionsByToolset() {
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(true);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        // Matching toolset
        List<ToolDefinition> matching = registry.getDefinitions(Set.of("test-toolset"));
        assertThat(matching).hasSize(1);

        // Non-matching toolset
        List<ToolDefinition> nonMatching = registry.getDefinitions(Set.of("other"));
        assertThat(nonMatching).isEmpty();
    }

    @Test
    @DisplayName("Should return all definitions when toolset filter is null or empty")
    void shouldReturnAllDefinitionsWhenToolsetFilterNullOrEmpty() {
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(true);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        // Null toolset
        List<ToolDefinition> nullResult = registry.getDefinitions((Set<String>) null);
        assertThat(nullResult).hasSize(1);

        // Empty toolset
        List<ToolDefinition> emptyResult = registry.getDefinitions(Set.of());
        assertThat(emptyResult).hasSize(1);
    }

    @Test
    @DisplayName("Should collect toolsets from registered tools")
    void shouldCollectToolsets() {
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(true);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        Set<String> toolsets = registry.getToolsets();
        assertThat(toolsets).contains("test-toolset");
    }

    @Test
    @DisplayName("Should skip tools disabled by ManagedToolGateway")
    void shouldSkipToolsDisabledByGateway() {
        TestToolHandler handler = new TestToolHandler();
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of("testTool", handler));
        when(managedToolGateway.isEnabled("test_tool")).thenReturn(false);

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        List<ToolDefinition> defs = registry.getDefinitions();
        assertThat(defs).isEmpty();
    }

    @Test
    @DisplayName("Should register and execute dynamic tool")
    void shouldRegisterAndExecuteDynamicTool() {
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of());

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        // Initially empty
        assertThat(registry.getDefinitions()).isEmpty();

        // Register dynamic tool
        ToolDefinition dynDef = new ToolDefinition("dyn_tool", "dynamic tool",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        registry.registerDynamic("dyn_tool", dynDef, new TestToolHandler());

        // Should appear in definitions
        List<ToolDefinition> defs = registry.getDefinitions();
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("dyn_tool");

        // Should be executable
        ToolResult result = registry.execute("dyn_tool", "call-3", "{}", LAST_MSG, SESSION);
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("Should deregister dynamic tool")
    void shouldDeregisterDynamicTool() {
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of());

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        ToolDefinition dynDef = new ToolDefinition("dyn_tool", "dynamic tool",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        registry.registerDynamic("dyn_tool", dynDef, new TestToolHandler());
        assertThat(registry.getDefinitions()).hasSize(1);

        registry.deregisterDynamic("dyn_tool");

        assertThat(registry.getDefinitions()).isEmpty();
        ToolResult result = registry.execute("dyn_tool", "call-4", "{}", LAST_MSG, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
    }

    @Test
    @DisplayName("Should return empty definitions and toolsets when no beans registered")
    void shouldReturnEmptyWhenNoBeans() {
        when(context.getBeansWithAnnotation(AgentTool.class))
            .thenReturn(Map.of());

        SpringToolRegistry registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans();

        assertThat(registry.getDefinitions()).isEmpty();
        assertThat(registry.getToolsets()).isEmpty();
    }

    // ── Test fixture: a real @AgentTool-annotated handler ──

    @AgentTool(name = "test_tool", description = "A test tool for unit testing.", toolset = "test-toolset")
    static class TestToolHandler implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok("test-ok");
        }
    }
}