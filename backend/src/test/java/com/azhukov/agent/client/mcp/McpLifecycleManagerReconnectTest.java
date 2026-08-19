package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
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
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * L40 test: verify that McpToolHandler only triggers reconnect on actual connection
 * errors, NOT on tool execution errors (e.g. IOException from a tool that simply failed).
 * The fix removed the `e instanceof IOException` catch-all from the reconnect condition.
 */
class McpLifecycleManagerReconnectTest {

    private McpLifecycleManager createManagerWithMockClient(String serverName, McpSyncClient mockClient) throws Exception {
        AgentProperties props = new AgentProperties();
        var serverProps = new AgentProperties.McpProperties.ServerProperties();
        serverProps.setName(serverName);
        serverProps.setTransport("stdio");
        serverProps.setCommand("echo");
        props.getMcp().getServers().add(serverProps);
        props.getMcp().setEnabled(true);

        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        // Use reflection to put a mock state into the clients map
        var clientsField = McpLifecycleManager.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) clientsField.get(mgr);

        // Find the McpServerState record (inner class)
        Class<?> stateClass = null;
        for (Class<?> inner : McpLifecycleManager.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("McpServerState")) {
                stateClass = inner;
                break;
            }
        }
        assertThat(stateClass).isNotNull();
        var stateConstructor = stateClass.getDeclaredConstructors()[0];
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance(serverProps, mockClient, List.of());
        clients.put(serverName, state);

        return mgr;
    }

    @Test
    void toolExecutionErrorDoesNotTriggerReconnect() throws Exception {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.callTool(any())).thenThrow(new RuntimeException("Tool error: invalid arguments"));

        McpLifecycleManager mgr = createManagerWithMockClient("test-srv", mockClient);

        var handler = mgr.new McpToolHandler("test-srv", "someTool");
        ToolResult result = handler.execute("{}", null, Session.create("u", "noop", ""));

        // The tool should fail
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("MCP tool failed");
        // The error should NOT mention "connection error" since it's a tool execution error
        assertThat(result.error()).doesNotContain("connection error");
        // Verify the client was NOT closed (no reconnect for tool execution errors)
        verify(mockClient, never()).close();
    }

    @Test
    void connectionErrorTriggersReconnectPath() throws Exception {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.callTool(any())).thenThrow(new RuntimeException("Connection refused"));

        McpLifecycleManager mgr = createManagerWithMockClient("test-srv2", mockClient);

        var handler = mgr.new McpToolHandler("test-srv2", "someTool");
        ToolResult result = handler.execute("{}", null, Session.create("u", "noop", ""));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("MCP tool failed");
        // "Connection refused" contains "refused" which is a connection error keyword
        // so the reconnect path should be triggered (the client entry removed from map)
    }

    @Test
    void ioExceptionWithoutConnectionKeywordsDoesNotReconnect() throws Exception {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        // An IOException that is NOT a connection error (e.g. tool returned bad data)
        when(mockClient.callTool(any())).thenThrow(new RuntimeException(new java.io.IOException("tool returned malformed response")));

        McpLifecycleManager mgr = createManagerWithMockClient("test-srv3", mockClient);

        var handler = mgr.new McpToolHandler("test-srv3", "someTool");
        ToolResult result = handler.execute("{}", null, Session.create("u", "noop", ""));

        assertThat(result.success()).isFalse();
        // Before the fix, any IOException would trigger reconnect.
        // After the fix, "tool returned malformed response" does NOT contain connection keywords.
        verify(mockClient, never()).close();
    }
}