package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.security.McpResponseScanner;
import com.azhukov.agent.security.McpToolDefinitionScanner;
import com.azhukov.agent.security.SlidingWindowRateLimiter;
import com.azhukov.agent.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.security.ToolFingerprintStore;
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

class McpLifecycleManagerNewFeaturesTest {

    @Test
    void sanitizeError_stripsBearerTokens() {
        assertThat(McpLifecycleManager.sanitizeError("Bearer sk-abc123 error"))
            .contains("[REDACTED]")
            .doesNotContain("sk-abc123");
    }

    @Test
    void sanitizeError_stripsGitHubTokens() {
        assertThat(McpLifecycleManager.sanitizeError("ghp_abcdef123456 leaked"))
            .contains("[REDACTED]")
            .doesNotContain("ghp_abcdef123456");
    }

    @Test
    void sanitizeError_stripsPasswordParams() {
        assertThat(McpLifecycleManager.sanitizeError("password=secret123 failed"))
            .contains("[REDACTED]")
            .doesNotContain("secret123");
    }

    @Test
    void sanitizeError_stripsKeyParams() {
        assertThat(McpLifecycleManager.sanitizeError("key=mysecretkey error"))
            .contains("[REDACTED]")
            .doesNotContain("mysecretkey");
    }

    @Test
    void sanitizeError_stripsSecretParams() {
        assertThat(McpLifecycleManager.sanitizeError("secret=topsecret error"))
            .contains("[REDACTED]")
            .doesNotContain("topsecret");
    }

    @Test
    void sanitizeError_returnsNullForNull() {
        assertThat(McpLifecycleManager.sanitizeError(null)).isNull();
    }

    @Test
    void sanitizeError_returnsEmptyForEmpty() {
        assertThat(McpLifecycleManager.sanitizeError("")).isEmpty();
    }

    @Test
    void sanitizeError_preservesNonCredentialText() {
        assertThat(McpLifecycleManager.sanitizeError("Connection refused"))
            .isEqualTo("Connection refused");
    }

    @Test
    void buildSafeEnv_filtersToSafeKeys() {
        // userEnv provides explicit vars
        Map<String, String> userEnv = Map.of("MY_API_KEY", "secret123", "CUSTOM_VAR", "val");
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(userEnv);

        // User-specified env vars should be passed through
        assertThat(result).containsEntry("MY_API_KEY", "secret123");
        assertThat(result).containsEntry("CUSTOM_VAR", "val");

        // PATH should be in the safe set (it's in the process environment)
        if (System.getenv().containsKey("PATH")) {
            assertThat(result).containsKey("PATH");
        }
        // HOME should be safe
        if (System.getenv().containsKey("HOME")) {
            assertThat(result).containsKey("HOME");
        }
    }

    @Test
    void buildSafeEnv_passesXdgVars() {
        // XDG_ vars should pass through from the process environment
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(Map.of());
        // If XDG_RUNTIME_DIR is set in the test environment, it should pass
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("XDG_")) {
                assertThat(result).containsKey(key);
            }
        }
    }

    @Test
    void buildSafeEnv_doesNotLeakUnsafeVars() {
        // Set a fake env var in userEnv - it should pass through (user explicitly specified it)
        // But process env vars that are not in the safe set should NOT be leaked
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(null);
        // Check that potentially sensitive env vars are not included (unless in safe set)
        for (String key : System.getenv().keySet()) {
            if (!key.equals("PATH") && !key.equals("HOME") && !key.equals("USER")
                && !key.equals("LANG") && !key.equals("LC_ALL") && !key.equals("TERM")
                && !key.equals("SHELL") && !key.equals("TMPDIR") && !key.startsWith("XDG_")) {
                assertThat(result).doesNotContainKey(key);
            }
        }
    }

    @Test
    void buildSafeEnv_handlesNullUserEnv() {
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(null);
        // Should not throw, should contain safe vars from process env
        assertThat(result).isNotNull();
    }

    @Test
    void refreshTools_updatesStateWhenToolListChanges() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        // Inject a client with initial tools
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(tool1));

        // Simulate server now returning different tools
        McpSchema.Tool tool2 = McpSchema.Tool.builder("tool2").title("t").description("d").inputSchema(Map.of()).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool2), ""));

        manager.refreshTools("srv");

        // Verify stale tool was deregistered and new tool was registered
        verify(registry).deregisterDynamic("srv__tool1");
        verify(registry).registerDynamic(eq("srv__tool2"), any(), any());

        // Verify state was updated
        List<McpLifecycleManager.McpServerInfo> servers = manager.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).toolNames()).containsExactly("tool2");
    }

    @Test
    void refreshTools_doesNothingWhenNoChange() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(tool1));

        // Same tools returned
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool1), ""));

        manager.refreshTools("srv");

        // Should NOT deregister or register anything
        verify(registry, never()).deregisterDynamic(any());
        verify(registry, never()).registerDynamic(any(), any(), any());
    }

    @Test
    void refreshTools_handlesMissingServerGracefully() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        // Should not throw
        manager.refreshTools("nonexistent");
    }

    @Test
    void reconnect_closesExistingAndSchedulesReconnect() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setTransport("stdio");
        server.setCommand("/nonexistent-binary-xyz");
        properties.getMcp().getServers().add(server);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(mock(ToolRegistry.class));

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        // Inject existing client
        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        manager.reconnect("srv");

        // Existing client should be closed
        verify(client).close();
        // Server should be removed from clients map
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void reconnect_unknownServerLogsWarning() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        // Should not throw
        manager.reconnect("nonexistent");
    }

    @Test
    void mcpToolHandlerStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("Bearer sk-secret123456 failed"));
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", null, null);

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.error()).contains("[REDACTED]");
        assertThat(toolResult.error()).doesNotContain("sk-secret123456");
    }

    @Test
    void executeToolStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("key=supersecret leaked"));
        injectClient(manager, "srv", client, List.of());

        try {
            manager.executeTool("srv", "tool", "{}");
            org.assertj.core.api.Assertions.fail("Expected exception");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("[REDACTED]");
            assertThat(e.getMessage()).doesNotContain("supersecret");
        }
    }

    @Test
    void readResourceStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class))).thenThrow(new RuntimeException("password=hunter2 error"));
        injectClient(manager, "srv", client, List.of());

        try {
            manager.readResource("srv", "resource://x");
            org.assertj.core.api.Assertions.fail("Expected exception");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("[REDACTED]");
            assertThat(e.getMessage()).doesNotContain("hunter2");
        }
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