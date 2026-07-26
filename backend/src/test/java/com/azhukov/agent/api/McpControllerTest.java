package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpControllerTest {

    @Test
    void listServers() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listServers()).thenReturn(List.of(new McpLifecycleManager.McpServerInfo("s", "http://x", "stdio", 0, List.of())));
        McpController c = new McpController(mgr, new AgentProperties());
        assertThat(c.listServers()).hasSize(1);
    }

    @Test
    void connect() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        doNothing().when(mgr).connect(any(AgentProperties.McpProperties.ServerProperties.class));
        McpController c = new McpController(mgr, new AgentProperties());
        String res = c.connect(Map.of("name", "n", "transport", "stdio", "command", "cmd"));
        assertThat(res).isEqualTo("OK");
        verify(mgr).connect(any(AgentProperties.McpProperties.ServerProperties.class));
    }
}
