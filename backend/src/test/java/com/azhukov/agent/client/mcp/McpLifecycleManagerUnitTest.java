package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.security.McpResponseScanner;
import com.azhukov.agent.security.McpToolDefinitionScanner;
import com.azhukov.agent.security.SlidingWindowRateLimiter;
import com.azhukov.agent.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.security.ToolFingerprintStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpLifecycleManagerUnitTest {

    @Test
    void convertsToolDefinitionFromInputSchema() {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string", "description", "Person name")),
            "required", List.of("name")
        );

        var tool = McpSchema.Tool.builder("greet", inputSchema)
            .description("Greets a person")
            .build();

        ToolDefinition definition = McpLifecycleManager.convertToolDefinition("test__greet", tool);
        assertThat(definition.name()).isEqualTo("test__greet");
        assertThat(definition.description()).isEqualTo("Greets a person");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) definition.parameters().get("properties");
        assertThat(props).containsKey("name");
        @SuppressWarnings("unchecked")
        List<String> req = (List<String>) definition.parameters().get("required");
        assertThat(req).contains("name");
    }

    @Test
    void executeToolFailsWhenServerNotConnected() {
        AgentProperties props = new AgentProperties();
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);
        McpLifecycleManager mgr = new McpLifecycleManager(props, mapper, ctx, null, null, null, null, null);

        assertThatThrownBy(() -> mgr.executeTool("missing", "tool", "{}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not connected");
    }

    @Test
    void readResourceFailsWhenServerNotConnected() {
        AgentProperties props = new AgentProperties();
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);
        McpLifecycleManager mgr = new McpLifecycleManager(props, mapper, ctx, null, null, null, null, null);

        assertThatThrownBy(() -> mgr.readResource("missing", "file://x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not connected");
    }

    @Test
    void listDiscoveredToolsIsEmptyWhenNoClients() {
        AgentProperties props = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx, null, null, null, null, null);
        assertThat(mgr.listDiscoveredTools()).isEmpty();
    }

    @Test
    void listServersIsEmptyWhenMcpDisabled() {
        AgentProperties props = new AgentProperties();
        props.getMcp().setEnabled(false);
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx, null, null, null, null, null);
        mgr.connectConfiguredServers();
        assertThat(mgr.listServers()).isEmpty();
    }

    @Test
    void closeAllClearsClients() {
        AgentProperties props = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx, null, null, null, null, null);
        mgr.closeAll();
        assertThat(mgr.listServers()).isEmpty();
    }

    @Test
    void mcpToolHandlerExecuteDelegates() {
        AgentProperties props = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());
        var handler = mgr.new McpToolHandler("srv", "tool");
        var r = handler.execute("{}", null, com.azhukov.agent.core.model.Session.create("u","noop",""));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not connected");
    }
}
