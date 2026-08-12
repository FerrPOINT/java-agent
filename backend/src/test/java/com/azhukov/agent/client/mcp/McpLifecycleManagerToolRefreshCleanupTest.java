package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * REM-5: Verify that toolRefreshFutures are cleaned up when a client is removed
 * during reconnect or error path.
 */
class McpLifecycleManagerToolRefreshCleanupTest {

    @Test
    void reconnect_cancelsAndRemovesToolRefreshFuture() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ApplicationContext applicationContext = mock(ApplicationContext.class);

        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, applicationContext);

        // Set up server config via the proper API
        AgentProperties.McpProperties.ServerProperties serverProps = new AgentProperties.McpProperties.ServerProperties();
        serverProps.setName("test-server");
        serverProps.setTransport("stdio");
        serverProps.setCommand("echo");
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().add(serverProps);

        // Access the toolRefreshFutures map via reflection
        Map<String, ScheduledFuture<?>> toolRefreshFutures = getToolRefreshFutures(manager);
        Map<String, Object> clients = getClientsMap(manager);

        // Manually add a fake tool refresh future
        ScheduledFuture<?> fakeFuture = mock(ScheduledFuture.class);
        toolRefreshFutures.put("test-server", fakeFuture);

        // Manually add a fake client state
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Object state = createMcpServerState(serverProps, mockClient, List.of());
        clients.put("test-server", state);

        // Call reconnect — should cancel and remove the tool refresh future
        manager.reconnect("test-server");

        // The tool refresh future should be cancelled and removed
        verify(fakeFuture).cancel(false);
        assertThat(toolRefreshFutures).doesNotContainKey("test-server");
    }

    @Test
    void toolHandlerErrorPath_cancelsToolRefreshFuture() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ApplicationContext applicationContext = mock(ApplicationContext.class);

        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, applicationContext);

        // Set up server config
        AgentProperties.McpProperties.ServerProperties serverProps = new AgentProperties.McpProperties.ServerProperties();
        serverProps.setName("error-server");
        serverProps.setTransport("stdio");
        serverProps.setCommand("echo");
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().add(serverProps);

        Map<String, ScheduledFuture<?>> toolRefreshFutures = getToolRefreshFutures(manager);
        Map<String, Object> clients = getClientsMap(manager);

        // Add a fake tool refresh future
        ScheduledFuture<?> fakeFuture = mock(ScheduledFuture.class);
        toolRefreshFutures.put("error-server", fakeFuture);

        // Add a fake client
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Object state = createMcpServerState(serverProps, mockClient, List.of());
        clients.put("error-server", state);

        // Create a McpToolHandler and simulate a connection error
        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("error-server", "some_tool");

        // Make callTool throw a connection error
        when(mockClient.callTool(any())).thenThrow(new RuntimeException("connection refused"));

        ToolResult result = handler.execute("{}", Message.assistant("test", 1), mock(Session.class));

        // The result should be a failure
        assertThat(result.success()).isFalse();

        // The tool refresh future should have been cancelled and removed
        verify(fakeFuture).cancel(false);
        assertThat(toolRefreshFutures).doesNotContainKey("error-server");
        assertThat(clients).doesNotContainKey("error-server");
    }

    @SuppressWarnings("unchecked")
    private Map<String, ScheduledFuture<?>> getToolRefreshFutures(McpLifecycleManager manager) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("toolRefreshFutures");
        field.setAccessible(true);
        return (Map<String, ScheduledFuture<?>>) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getClientsMap(McpLifecycleManager manager) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(manager);
    }

    private Object createMcpServerState(AgentProperties.McpProperties.ServerProperties props,
                                        McpSyncClient client, List<?> tools) throws Exception {
        Class<?> stateClass = Class.forName("com.azhukov.agent.client.mcp.McpLifecycleManager$McpServerState");
        Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(props, client, tools);
    }
}