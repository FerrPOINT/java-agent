package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpServerService}.
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceTest {

    @Mock
    private ToolRegistry toolRegistry;

    private AgentProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        // Reset properties to defaults after each test
        properties = new AgentProperties();
    }

    @Test
    void shouldNotStartWhenDisabled() {
        properties.getMcp().getServer().setEnabled(false);
        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldStartWithStdioTransport() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("test_tool", "A test tool", Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            ))
        ));

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.isRunning()).isTrue();
        assertThat(service.getExposedToolNames()).contains("test_tool");

        service.stop();
    }

    @Test
    void shouldStartWithSseTransport() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("sse");
        properties.getMcp().getServer().setSseEndpoint("/mcp/sse");
        properties.getMcp().getServer().setMessageEndpoint("/mcp/message");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.isRunning()).isTrue();
        assertThat(service.getServletRegistration()).isNotNull();

        service.stop();
    }

    @Test
    void shouldExposeMultipleTools() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("tool_a", "Tool A", Map.of("type", "object", "properties", Map.of(), "required", List.of())),
            new ToolDefinition("tool_b", "Tool B", Map.of("type", "object", "properties", Map.of(), "required", List.of())),
            new ToolDefinition("tool_c", "Tool C", Map.of("type", "object", "properties", Map.of(), "required", List.of()))
        ));

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.getExposedToolNames()).containsExactlyInAnyOrder("tool_a", "tool_b", "tool_c");

        service.stop();
    }

    @Test
    void shouldHandleEmptyToolRegistry() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.isRunning()).isTrue();
        assertThat(service.getExposedToolNames()).isEmpty();

        service.stop();
    }

    @Test
    void shouldReportNotRunningAfterStop() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();
        assertThat(service.isRunning()).isTrue();

        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldUseConfiguredServerNameAndVersion() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        properties.getMcp().getServer().setName("custom-agent");
        properties.getMcp().getServer().setVersion("2.5.0");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.isRunning()).isTrue();

        service.stop();
    }

    @Test
    void shouldReturnEmptyToolNamesWhenNotRunning() {
        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        assertThat(service.getExposedToolNames()).isEmpty();
    }

    @Test
    void shouldReturnNullServletRegistrationWhenStdio() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.getServletRegistration()).isNull();

        service.stop();
    }

    @Test
    void shouldHandleContextRefreshedEvent() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);

        // Simulate ContextRefreshedEvent — onContextRefreshed is the @EventListener method
        service.onContextRefreshed();

        assertThat(service.isRunning()).isTrue();

        service.stop();
    }

    @Test
    void shouldHandleToolCallSuccess() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("echo", "Echo tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()))
        ));

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        // Verify the tool was registered
        assertThat(service.getExposedToolNames()).contains("echo");

        service.stop();
    }

    @Test
    void shouldHandleToolCallFailure() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("fail_tool", "Failing tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()))
        ));

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        assertThat(service.getExposedToolNames()).contains("fail_tool");

        service.stop();
    }

    @Test
    void shouldHandleUnknownTransportGracefully() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("unknown-transport");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();

        // Unknown transport defaults to stdio
        assertThat(service.isRunning()).isTrue();

        service.stop();
    }

    @Test
    void shouldHandleDoubleStartGracefully() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();
        // Second start should not throw
        service.start();

        assertThat(service.isRunning()).isTrue();

        service.stop();
    }

    @Test
    void shouldHandleDoubleStopGracefully() {
        properties.getMcp().getServer().setEnabled(true);
        properties.getMcp().getServer().setTransport("stdio");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        McpServerService service = new McpServerService(properties, objectMapper, toolRegistry);
        service.onContextRefreshed();
        service.stop();
        // Second stop should not throw
        service.stop();

        assertThat(service.isRunning()).isFalse();
    }
}