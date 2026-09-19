package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-i (Hermes tools/mcp_stdio_watchdog.py + in-flight teardown parity).
 */
@ExtendWith(MockitoExtension.class)
class McpStdioWatchdogTest {

    @Mock
    private McpSyncClient wedgedClient;

    private AgentProperties properties;
    private McpLifecycleManager manager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setCommand("cat"); // never speaks MCP; holds the pipe open
        server.setTimeout(0.5);
        properties.getMcp().getServers().add(server);
        manager = new McpLifecycleManager(properties, new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null, null, null, null, null);
    }

    @Test
    void watchdogScriptResolvesFromTestClasspath() {
        String script = manager.mcpStdioWatchdogScript();
        assertThat(script).isNotNull();
        assertThat(java.nio.file.Files.isExecutable(java.nio.file.Path.of(script))).isTrue();
    }

    @Test
    void stdioCommandIsWrappedWithWatchdogOnPosix() {
        AgentProperties.McpProperties.ServerProperties server = properties.getMcp().getServers().get(0);
        List<String> argv = manager.stdioLaunchCommand(server, "npx");
        assertThat(argv).isNotEmpty();
        assertThat(argv.get(0)).isEqualTo("sh");
        assertThat(argv.get(1)).endsWith(".sh");
        assertThat(argv.get(2)).isEqualTo("--ppid");
        assertThat(Long.parseLong(argv.get(3))).isPositive();
        assertThat(argv.get(4)).isEqualTo("--");
        assertThat(argv.get(5)).isEqualTo("npx");
    }

    @Test
    void watchdogDisabledSpawnsDirectly() {
        AgentProperties.McpProperties.ServerProperties server = properties.getMcp().getServers().get(0);
        server.setStdioParentDeathWatchdog(false);
        List<String> argv = manager.stdioLaunchCommand(server, "npx");
        assertThat(argv.get(0)).isEqualTo("npx");
        assertThat(argv).hasSize(1);
    }

    @Test
    void extraServerArgsSurviveTheWrap() {
        AgentProperties.McpProperties.ServerProperties server = properties.getMcp().getServers().get(0);
        server.getArgs().addAll(List.of("-y", "mcp-remote", "https://x"));
        List<String> argv = manager.stdioLaunchCommand(server, "npx");
        assertThat(argv.subList(argv.indexOf("--") + 1, argv.size()))
            .containsExactly("npx", "-y", "mcp-remote", "https://x");
    }

    @Test
    void wedgedClientIsClosedAndReconnectScheduledOnTimeout() throws Exception {
        // callTool never returns => timeout path
        when(wedgedClient.callTool(any(McpSchema.CallToolRequest.class)))
            .thenAnswer(inv -> { Thread.sleep(10_000); return null; });

        AgentProperties.McpProperties.ServerProperties server = properties.getMcp().getServers().get(0);
        var state = new McpLifecycleManager.McpServerState(server, wedgedClient, List.of());
        manager.getClientsForTest().put("srv", state);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            var future = ex.submit(() -> manager.executeTool("srv", "slow_tool", "{}"));
            assertThatThrownBy(() -> future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasMessageContaining("timed out");
        } finally {
            ex.shutdownNow();
        }

        // The wedged client must have been closed (verifiable via mock)
        verify(wedgedClient).close();
    }

    @Test
    void healthyCallNeverTouchesTeardown() throws Exception {
        McpSchema.CallToolResult ok = new McpSchema.CallToolResult(List.of(
            new McpSchema.TextContent(null, "done")), false, null, null);
        when(wedgedClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(ok);

        AgentProperties.McpProperties.ServerProperties server = properties.getMcp().getServers().get(0);
        var state = new McpLifecycleManager.McpServerState(server, wedgedClient, List.of());
        manager.getClientsForTest().put("srv", state);

        McpSchema.CallToolResult result = manager.executeTool("srv", "fast_tool", "{}");
        assertThat(result).isSameAs(ok);
        verify(wedgedClient, never()).close();
    }
}
