package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Focused unit tests for {@link McpController} — covers success,
 * bad input/error/edge cases for listing servers, listing server tools,
 * invoking tools, and reading resources.
 */
@ExtendWith(MockitoExtension.class)
class McpControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private McpLifecycleManager mcpLifecycleManager;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        McpController controller = new McpController(mcpLifecycleManager, properties, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    // ── List servers ──

    @Test
    void listServersReturnsAllServers() throws Exception {
        when(mcpLifecycleManager.listServers()).thenReturn(List.of(
            new McpLifecycleManager.McpServerInfo("server-a", "http://localhost:3000", "stdio", 2, List.of("tool1", "tool2")),
            new McpLifecycleManager.McpServerInfo("server-b", "http://localhost:3001", "http", 0, List.of())
        ));

        mockMvc.perform(get("/api/v1/mcp/servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("server-a"))
            .andExpect(jsonPath("$[0].baseUrl").value("http://localhost:3000"))
            .andExpect(jsonPath("$[0].transport").value("stdio"))
            .andExpect(jsonPath("$[0].toolCount").value(2))
            .andExpect(jsonPath("$[0].toolNames[0]").value("tool1"))
            .andExpect(jsonPath("$[1].name").value("server-b"))
            .andExpect(jsonPath("$[1].toolCount").value(0));
    }

    @Test
    void listServersReturnsEmptyWhenNoServers() throws Exception {
        when(mcpLifecycleManager.listServers()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/mcp/servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── List server tools ──

    @Test
    void listServerToolsReturnsToolsForNamedServer() throws Exception {
        ToolDefinition toolDef = new ToolDefinition("server-a__tool1", "Does tool1 things", Map.of("type", "object"));
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of(
            new McpLifecycleManager.DiscoveredTool("server-a", "tool1", toolDef),
            new McpLifecycleManager.DiscoveredTool("server-b", "tool2", new ToolDefinition("server-b__tool2", "desc", Map.of()))
        ));

        mockMvc.perform(get("/api/v1/mcp/servers/{name}/tools", "server-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].serverName").value("server-a"))
            .andExpect(jsonPath("$[0].toolName").value("tool1"))
            .andExpect(jsonPath("$[0].definition.name").value("server-a__tool1"));
    }

    @Test
    void listServerToolsReturnsEmptyWhenNoMatchingServer() throws Exception {
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of(
            new McpLifecycleManager.DiscoveredTool("server-a", "tool1",
                new ToolDefinition("server-a__tool1", "desc", Map.of()))
        ));

        mockMvc.perform(get("/api/v1/mcp/servers/{name}/tools", "nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listServerToolsReturnsEmptyWhenNoDiscoveredTools() throws Exception {
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/mcp/servers/{name}/tools", "server-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Invoke tool ──

    @Test
    void invokeToolReturnsContentList() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("tool output text")),
            false,
            null,
            null
        );
        when(mcpLifecycleManager.executeTool(eq("server-a"), eq("tool1"), anyString()))
            .thenReturn(result);

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-a", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"key":"value"}
                    """))
            .andExpect(status().isOk())
            // Object::toString on TextContent returns the record's toString, not just the text
            .andExpect(jsonPath("$.content[0]").value(
                org.hamcrest.Matchers.containsString("tool output text")));
    }

    @Test
    void invokeToolWithEmptyArgsReturnsContent() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("empty args result")),
            false,
            null,
            null
        );
        when(mcpLifecycleManager.executeTool(eq("server-a"), eq("tool1"), anyString()))
            .thenReturn(result);

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-a", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0]").value(
                org.hamcrest.Matchers.containsString("empty args result")));
    }

    @Test
    void invokeToolMultipleContentItems() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(
                new McpSchema.TextContent("output1"),
                new McpSchema.TextContent("output2")
            ),
            false,
            null,
            null
        );
        when(mcpLifecycleManager.executeTool(anyString(), anyString(), anyString()))
            .thenReturn(result);

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-a", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"key":"value"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0]").value(
                org.hamcrest.Matchers.containsString("output1")))
            .andExpect(jsonPath("$.content[1]").value(
                org.hamcrest.Matchers.containsString("output2")));
    }

    @Test
    void invokeToolWhenServerNotConnectedReturns500() throws Exception {
        when(mcpLifecycleManager.executeTool(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("MCP server not connected: server-x"));

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-x", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"key":"value"}
                    """))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void invokeToolWhenExecutionFailsReturns500() throws Exception {
        when(mcpLifecycleManager.executeTool(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("MCP tool call failed: connection refused"));

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-a", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"key":"value"}
                    """))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void invokeToolWithEmptyContentList() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(),
            false,
            null,
            null
        );
        when(mcpLifecycleManager.executeTool(anyString(), anyString(), anyString()))
            .thenReturn(result);

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/tools/{toolName}", "server-a", "tool1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"key":"value"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    // ── Read resource ──

    @Test
    void readResourceReturnsContent() throws Exception {
        when(mcpLifecycleManager.readResource("server-a", "file:///example.txt"))
            .thenReturn("file content here");

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/resources", "server-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"uri":"file:///example.txt"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uri").value("file:///example.txt"))
            .andExpect(jsonPath("$.content").value("file content here"));

        verify(mcpLifecycleManager).readResource("server-a", "file:///example.txt");
    }

    @Test
    void readResourceWithBlankUriReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/servers/{name}/resources", "server-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"uri":""}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void readResourceWhenReadFailsReturns500() throws Exception {
        when(mcpLifecycleManager.readResource(anyString(), anyString()))
            .thenThrow(new RuntimeException("MCP read resource failed: connection error"));

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/resources", "server-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"uri":"file:///missing.txt"}
                    """))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void readResourceReturnsEmptyContent() throws Exception {
        when(mcpLifecycleManager.readResource("server-a", "file:///empty.txt"))
            .thenReturn("");

        mockMvc.perform(post("/api/v1/mcp/servers/{name}/resources", "server-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"uri":"file:///empty.txt"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uri").value("file:///empty.txt"))
            .andExpect(jsonPath("$.content").value(""));
    }
}