package com.azhukov.agent.health;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpHealthIndicatorTest {

    @Test
    void upWhenServersConnected() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listServers()).thenReturn(List.of(new McpLifecycleManager.McpServerInfo("s1", "http://x", "sse", 1, List.of("t1"))));
        McpHealthIndicator h = new McpHealthIndicator(mgr);
        assertThat(h.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void downWhenNoServers() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listServers()).thenReturn(List.of());
        McpHealthIndicator h = new McpHealthIndicator(mgr);
        assertThat(h.health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
