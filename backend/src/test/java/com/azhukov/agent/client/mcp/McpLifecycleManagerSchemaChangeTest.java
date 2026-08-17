package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for MCP schema change detection during tool refresh (Finding 8.1).
 * Verifies that refreshTools detects schema changes with same tool names
 * and skips re-registration when names and schemas are unchanged.
 */
class McpLifecycleManagerSchemaChangeTest {

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client,
                              List<McpSchema.Tool> tools) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> map = (ConcurrentHashMap<String, Object>) field.get(manager);
        Class<?> stateClass = Class.forName("com.azhukov.agent.client.mcp.McpLifecycleManager$McpServerState");
        AgentProperties.McpProperties.ServerProperties props = new AgentProperties.McpProperties.ServerProperties();
        props.setName(name);
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        map.put(name, ctor.newInstance(props, client, tools));
    }

    @Test
    void mcpRefreshDetectsSchemaChangeWithSameNames() throws Exception {
        AgentProperties properties = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(toolRegistry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx);

        // Initial tools with one schema
        McpSchema.Tool initialTool = McpSchema.Tool.builder("tool1")
            .title("t").description("d")
            .inputSchema(Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))))
            .build();

        // Refreshed tool: same name, different inputSchema (string → integer)
        McpSchema.Tool refreshedTool = McpSchema.Tool.builder("tool1")
            .title("t").description("d")
            .inputSchema(Map.of("type", "object", "properties", Map.of("name", Map.of("type", "integer"))))
            .build();

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools())
            .thenReturn(new McpSchema.ListToolsResult(List.of(refreshedTool), null));

        injectClient(manager, "srv", client, List.of(initialTool));

        // Call refreshTools — should detect schema change and re-register
        manager.refreshTools("srv");

        // Verify re-registration happened (registerDynamic called with new definition)
        verify(toolRegistry).registerDynamic(eq("srv__tool1"), any(), any());
    }

    @Test
    void mcpRefreshSkipsWhenNamesAndSchemasUnchanged() throws Exception {
        AgentProperties properties = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(toolRegistry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx);

        // Tools with same names and same schemas
        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1")
            .title("t").description("d")
            .inputSchema(Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))))
            .build();
        McpSchema.Tool tool2 = McpSchema.Tool.builder("tool2")
            .title("t2").description("d2")
            .inputSchema(Map.of("type", "object"))
            .build();

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools())
            .thenReturn(new McpSchema.ListToolsResult(List.of(tool1, tool2), null));

        injectClient(manager, "srv", client, List.of(tool1, tool2));

        // Call refreshTools — should detect no changes and skip re-registration
        manager.refreshTools("srv");

        // Verify registerDynamic was NOT called
        verify(toolRegistry, never()).registerDynamic(any(), any(), any());
        verify(toolRegistry, never()).deregisterDynamic(any());
    }
}