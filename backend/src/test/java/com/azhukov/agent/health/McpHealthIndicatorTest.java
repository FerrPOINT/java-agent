package com.azhukov.agent.health;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpHealthIndicatorTest {

    @Test
    void upWhenServersConnected() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listServers()).thenReturn(List.of(new McpLifecycleManager.McpServerInfo("s1", "http://x", "sse", 1, List.of("t1"))));
        AgentProperties props = new AgentProperties();
        props.getMcp().setEnabled(true);
        McpHealthIndicator h = new McpHealthIndicator(mgr, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("status");
    }

    @Test
    void upWithNotConfiguredWhenMcpDisabled() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        AgentProperties props = new AgentProperties();
        props.getMcp().setEnabled(false);
        McpHealthIndicator h = new McpHealthIndicator(mgr, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        assertThat(health.getDetails()).containsEntry("reason", "MCP is disabled");
    }

    @Test
    void upWithNotConfiguredWhenNoServersConnected() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listServers()).thenReturn(List.of());
        AgentProperties props = new AgentProperties();
        props.getMcp().setEnabled(true);
        McpHealthIndicator h = new McpHealthIndicator(mgr, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        assertThat(health.getDetails()).containsEntry("servers", 0);
    }
}