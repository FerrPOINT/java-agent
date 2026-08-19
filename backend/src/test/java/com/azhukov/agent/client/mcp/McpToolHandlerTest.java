package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
import com.azhukov.agent.core.security.SlidingWindowRateLimiter;
import com.azhukov.agent.core.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.core.security.ToolFingerprintStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolHandlerTest {

    @Test
    void executeReturnsOkOnSuccess() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "ok")), false, null, null);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isTrue();
        assertThat(toolResult.content()).contains("ok");
    }

    @Test
    void executeReturnsFailOnException() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("boom"));
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.error()).contains("boom");
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client, List<McpSchema.Tool> tools) throws Exception {
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
}