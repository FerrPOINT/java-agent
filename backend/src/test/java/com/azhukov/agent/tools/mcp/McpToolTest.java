package com.azhukov.agent.tools.mcp;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpToolTest {

    @Test
    void invokesServerToolAndReturnsContent() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = mock(io.modelcontextprotocol.spec.McpSchema.CallToolResult.class);
        io.modelcontextprotocol.spec.McpSchema.TextContent text = new io.modelcontextprotocol.spec.McpSchema.TextContent("hi");
        when(result.content()).thenReturn(java.util.List.of(text));
        when(mgr.executeTool("srv", "greet", "{\"name\":\"A\"}")).thenReturn(result);

        McpTool tool = new McpTool(mgr);
        ToolResult r = tool.execute("{\"server_name\":\"srv\",\"tool_name\":\"greet\",\"arguments\":\"{\\\"name\\\":\\\"A\\\"}\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("hi");
    }

    @Test
    void returnsFailureOnException() {
        McpLifecycleManager mgr = mock(McpLifecycleManager.class);
        when(mgr.executeTool("srv", "greet", "{}")).thenThrow(new RuntimeException("down"));

        McpTool tool = new McpTool(mgr);
        ToolResult r = tool.execute("{\"server_name\":\"srv\",\"tool_name\":\"greet\",\"arguments\":\"{}\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("down");
    }
}
