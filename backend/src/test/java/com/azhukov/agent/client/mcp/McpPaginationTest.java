package com.azhukov.agent.client.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Hermes parity tests for MCP nextCursor pagination (tools/mcp_tool.py _drain_paginated):
 * the cursor MUST be passed into the next listTools call — otherwise page 1 repeats forever.
 */
class McpPaginationTest {

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name).description("desc").build();
    }

    private static McpSchema.ListToolsResult result(List<McpSchema.Tool> tools, String nextCursor) {
        if (nextCursor != null) {
            return new McpSchema.ListToolsResult(tools, nextCursor);
        }
        return new McpSchema.ListToolsResult(tools, null);
    }

    private List<McpSchema.Tool> paginate(McpSyncClient client) throws Exception {
        Method m = McpLifecycleManager.class.getDeclaredMethod("listToolsWithPagination", McpSyncClient.class);
        m.setAccessible(true);
        McpLifecycleManager manager = new McpLifecycleManager(null, null, null, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<McpSchema.Tool> tools = (List<McpSchema.Tool>) m.invoke(manager, client);
        return tools;
    }

    @Test
    void paginationPassesCursorAndStops() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(result(List.of(tool("a")), "cursor-1"));
        when(client.listTools(anyString())).thenReturn(
            result(List.of(tool("b")), "cursor-2"),
            result(List.of(tool("c")), null));

        List<McpSchema.Tool> tools = paginate(client);

        assertEquals(3, tools.size(), "all three pages must be collected exactly once");
        verify(client, times(1)).listTools();
        verify(client, times(2)).listTools(anyString());
        verify(client).listTools("cursor-1");
        verify(client).listTools("cursor-2");
    }

    @Test
    void sameCursorTwiceStopsLoop() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(result(List.of(tool("a")), "loop"));
        when(client.listTools(anyString())).thenReturn(result(List.of(tool("a")), "loop"));

        List<McpSchema.Tool> tools = paginate(client);

        assertEquals(2, tools.size(), "looping server stops after the duplicate-cursor guard");
        verify(client, times(1)).listTools(anyString());
    }

    @Test
    void noCursorSinglePage() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(result(List.of(tool("only")), null));

        List<McpSchema.Tool> tools = paginate(client);

        assertEquals(1, tools.size());
        verify(client, never()).listTools(anyString());
    }
}
