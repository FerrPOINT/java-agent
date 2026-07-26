package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
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
        McpController c = new McpController(mgr, new AgentProperties(), new ObjectMapper());
        assertThat(c.listServers()).hasSize(1);
    }

    @Test
    void listServerTools() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.listDiscoveredTools()).thenReturn(List.of(new McpLifecycleManager.DiscoveredTool("s", "t", null)));
        McpController c = new McpController(mgr, new AgentProperties(), new ObjectMapper());
        assertThat(c.listServerTools("s")).hasSize(1);
    }

    @Test
    void invokeTool() throws Exception {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent("ok")))
            .build();
        when(mgr.executeTool("s", "t", "{\"x\":1}")).thenReturn(result);
        McpController c = new McpController(mgr, new AgentProperties(), new ObjectMapper());
        @SuppressWarnings("unchecked")
        Map<String, Object> res = c.invokeTool("s", "t", Map.of("x", 1));
        List<String> content = (List<String>) res.get("content");
        assertThat(content).anyMatch(s -> s.contains("ok"));
    }

    @Test
    void readResource() throws Exception {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.readResource("s", "file://x")).thenReturn("content");
        McpController c = new McpController(mgr, new AgentProperties(), new ObjectMapper());
        Map<String, String> res = c.readResource("s", Map.of("uri", "file://x"));
        assertThat(res.get("content")).isEqualTo("content");
    }
}
