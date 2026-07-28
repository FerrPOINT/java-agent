package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
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
import static org.mockito.Mockito.*;

class McpLifecycleManagerStateTest {

    @Test
    void listServersReturnsConnectedInfo() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = McpSchema.Tool.builder("test").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv1", client, List.of(tool));

        List<McpLifecycleManager.McpServerInfo> servers = manager.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).toolCount()).isEqualTo(1);
        assertThat(servers.get(0).toolNames()).contains("test");
    }

    @Test
    void listDiscoveredToolsReturnsDefinitions() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv1", client, List.of(tool));

        List<McpLifecycleManager.DiscoveredTool> discovered = manager.listDiscoveredTools();
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).toolName()).isEqualTo("tool1");
    }

    @Test
    void readResourceJoinsContents() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents("uri", "text/plain", "hello"))));
        injectClient(manager, "srv", client, List.of());

        String result = manager.readResource("srv", "resource://x");
        assertThat(result).contains("hello");
    }

    @Test
    void executeToolCallsClient() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(null, "done")), false, null, null);
        when(client.callTool(any())).thenReturn(callResult);
        injectClient(manager, "srv", client, List.of());

        McpSchema.CallToolResult result = manager.executeTool("srv", "tool1", "{\"x\":1}");
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void closeAllClosesClients() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        manager.closeAll();

        verify(client).close();
        assertThat(manager.listServers()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client, List<McpSchema.Tool> tools) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> map = (ConcurrentHashMap<String, Object>) field.get(manager);
        Object state = createState(name, client, tools);
        map.put(name, state);
    }

    private Object createState(String name, McpSyncClient client, List<McpSchema.Tool> tools) throws Exception {
        Class<?> stateClass = Class.forName("com.azhukov.agent.client.mcp.McpLifecycleManager$McpServerState");
        AgentProperties.McpProperties.ServerProperties props = new AgentProperties.McpProperties.ServerProperties();
        props.setName(name);
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(props, client, tools);
    }
}
