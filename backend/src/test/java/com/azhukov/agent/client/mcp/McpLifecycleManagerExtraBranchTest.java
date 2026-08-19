package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage tests for {@link McpLifecycleManager}.
 * Covers closeAll, listDiscoveredTools, readResource, executeTool, McpToolHandler edge cases.
 */
class McpLifecycleManagerExtraBranchTest {

    @Test
    void closeAll_clearsClientsAndShutsDownExecutors() throws Exception {
        AgentProperties properties = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, null, null, null, null, null);

        // Inject a mock client
        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        manager.closeAll();

        verify(client).close();
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void closeAll_withNullClients_doesNotThrow() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        manager.closeAll(); // should not throw
    }

    @Test
    void closeAll_multipleClients_allClosed() throws Exception {
        AgentProperties properties = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, null, null, null, null, null);

        McpSyncClient client1 = mock(McpSyncClient.class);
        McpSyncClient client2 = mock(McpSyncClient.class);
        injectClient(manager, "srv1", client1, List.of());
        injectClient(manager, "srv2", client2, List.of());

        manager.closeAll();

        verify(client1).close();
        verify(client2).close();
    }

    @Test
    void readResource_notConnected_throwsIllegalStateException() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        assertThatThrownBy(() -> manager.readResource("nonexistent", "resource://x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP server not connected");
    }

    @Test
    void readResource_throwsException_credentialsSanitized() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
            .thenThrow(new RuntimeException("password=secret123 error"));
        injectClient(manager, "srv", client, List.of());

        assertThatThrownBy(() -> manager.readResource("srv", "resource://x"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("[REDACTED]")
            .hasMessageNotContaining("secret123");
    }

    @Test
    void executeTool_notConnected_throwsIllegalStateException() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        assertThatThrownBy(() -> manager.executeTool("nonexistent", "tool", "{}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP server not connected");
    }

    @Test
    void executeTool_throwsException_credentialsSanitized() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("Bearer sk-abc123 error"));
        injectClient(manager, "srv", client, List.of());

        assertThatThrownBy(() -> manager.executeTool("srv", "tool", "{}"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("[REDACTED]")
            .hasMessageNotContaining("sk-abc123");
    }

    @Test
    void executeTool_invalidJson_throwsException() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        assertThatThrownBy(() -> manager.executeTool("srv", "tool", "not valid json {{{"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void listDiscoveredTools_empty_returnsEmptyList() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        assertThat(manager.listDiscoveredTools()).isEmpty();
    }

    @Test
    void listDiscoveredTools_withTools_returnsAll() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        McpSchema.Tool tool2 = McpSchema.Tool.builder("tool2").title("t").description("d").inputSchema(Map.of()).build();
        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of(tool1, tool2));

        var tools = manager.listDiscoveredTools();
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).serverName()).isEqualTo("srv");
        assertThat(tools.get(0).toolName()).isEqualTo("tool1");
        assertThat(tools.get(1).toolName()).isEqualTo("tool2");
    }

    @Test
    void listServers_empty_returnsEmptyList() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void listServers_withClient_returnsServerInfo() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of(tool));

        var servers = manager.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).name()).isEqualTo("srv");
        assertThat(servers.get(0).toolCount()).isEqualTo(1);
        assertThat(servers.get(0).toolNames()).containsExactly("tool1");
    }

    @Test
    void connectConfiguredServers_disabled_doesNothing() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(false);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        manager.connectConfiguredServers(); // should not throw
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void connectConfiguredServers_emptyServers_doesNothing() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        manager.connectConfiguredServers();
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void mcpToolHandler_executeThrows_returnsFailedResult() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("key=supersecret leaked"));
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult result = handler.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("[REDACTED]");
        assertThat(result.error()).doesNotContain("supersecret");
    }

    @Test
    void mcpToolHandler_executeWithNullArgs_returnsFailedResult() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        // Null arguments → JSON parse error → failed result
        ToolResult result = handler.execute(null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("MCP tool failed");
    }

    @Test
    void convertToolDefinition_nullInputSchema_returnsEmptySchema() {
        McpSchema.Tool tool = McpSchema.Tool.builder("test").description("desc").inputSchema(Map.of()).build();
        var def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        assertThat(def.name()).isEqualTo("srv__test");
        assertThat(def.description()).isEqualTo("desc");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = def.parameters();
        assertThat(params.get("type")).isEqualTo("object");
        assertThat(params.get("properties")).isNotNull();
    }

    @Test
    void convertToolDefinition_nullDescription_throwsNPE() {
        McpSchema.Tool tool = McpSchema.Tool.builder("test").inputSchema(Map.of()).build();
        // ToolDefinition requires non-null description
        assertThatThrownBy(() -> McpLifecycleManager.convertToolDefinition("srv__test", tool))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("description must not be null");
    }

    @Test
    void convertToolDefinition_withPropertiesAndRequired() {
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string", "description", "Name")),
            "required", List.of("name")
        );
        McpSchema.Tool tool = McpSchema.Tool.builder("test").description("test").inputSchema(inputSchema).build();
        var def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = def.parameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertThat(props).containsKey("name");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertThat(required).containsExactly("name");
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