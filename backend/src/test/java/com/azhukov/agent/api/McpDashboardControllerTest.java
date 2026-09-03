package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class McpDashboardControllerTest {

    private MockMvc mockMvc;
    private AgentProperties properties;

    @Mock
    private McpLifecycleManager mcpLifecycleManager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        mockMvc = MockMvcBuilders.standaloneSetup(new McpDashboardController(
                mcpLifecycleManager, properties, new ObjectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listServersMergesConfiguredServerWithLiveToolsAndRedactsEnv() throws Exception {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("filesystem");
        server.setCommand("npx");
        server.setEnabled(false);
        server.getArgs().addAll(List.of("-y", "@modelcontextprotocol/server-filesystem"));
        server.getEnv().put("API_KEY", "sk-secret");
        server.getEnv().put("SAFE_FLAG", "plain");
        properties.getMcp().getServers().add(server);
        when(mcpLifecycleManager.listServers()).thenReturn(List.of(
            new McpLifecycleManager.McpServerInfo("filesystem", "", "stdio", 1, List.of("read_file"))
        ));

        mockMvc.perform(get("/api/mcp/servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.servers[0].name").value("filesystem"))
            .andExpect(jsonPath("$.servers[0].transport").value("stdio"))
            .andExpect(jsonPath("$.servers[0].command").value("npx"))
            .andExpect(jsonPath("$.servers[0].args[0]").value("-y"))
            .andExpect(jsonPath("$.servers[0].env.API_KEY").value("[REDACTED]"))
            .andExpect(jsonPath("$.servers[0].env.SAFE_FLAG").value("plain"))
            .andExpect(jsonPath("$.servers[0].enabled").value(false))
            .andExpect(jsonPath("$.servers[0].tools[0]").value("read_file"));
    }

    @Test
    void listServersIncludesLiveOnlyConnections() throws Exception {
        when(mcpLifecycleManager.listServers()).thenReturn(List.of(
            new McpLifecycleManager.McpServerInfo("remote", "https://example.test/mcp", "http", 1, List.of("search"))
        ));

        mockMvc.perform(get("/api/mcp/servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.servers[0].name").value("remote"))
            .andExpect(jsonPath("$.servers[0].transport").value("http"))
            .andExpect(jsonPath("$.servers[0].url").value("https://example.test/mcp"))
            .andExpect(jsonPath("$.servers[0].tools[0]").value("search"));
    }

    @Test
    void testServerReturnsLiveDiscoveredTools() throws Exception {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("filesystem");
        server.setCommand("npx");
        properties.getMcp().getServers().add(server);
        when(mcpLifecycleManager.listServers()).thenReturn(List.of(
            new McpLifecycleManager.McpServerInfo("filesystem", "", "stdio", 1, List.of("read_file"))
        ));
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of(
            new McpLifecycleManager.DiscoveredTool(
                "filesystem",
                "read_file",
                new ToolDefinition("mcp__filesystem__read_file", "Read files", Map.of("type", "object")))
        ));

        mockMvc.perform(post("/api/mcp/servers/filesystem/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.tools[0].name").value("read_file"))
            .andExpect(jsonPath("$.tools[0].description").value("Read files"))
            .andExpect(jsonPath("$.tools[0].schema_chars").isNumber())
            .andExpect(jsonPath("$.prompts").value(0))
            .andExpect(jsonPath("$.resources").value(0));
    }

    @Test
    void testServerReturns404ForUnknownServer() throws Exception {
        when(mcpLifecycleManager.listServers()).thenReturn(List.of());

        mockMvc.perform(post("/api/mcp/servers/missing/test"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Server 'missing' not found"));
    }

    @Test
    void catalogReturnsEmptyDiagnosticsInsteadOf404() throws Exception {
        mockMvc.perform(get("/api/mcp/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isArray())
            .andExpect(jsonPath("$.entries.length()").value(0))
            .andExpect(jsonPath("$.diagnostics[0].kind").value("unsupported"));
    }

    @Test
    void mutatingConfigEndpointsReturnExplicitNotImplemented() throws Exception {
        mockMvc.perform(post("/api/mcp/servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"srv\",\"command\":\"npx\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("MCP server config writes are not implemented in Java agent"));

        mockMvc.perform(put("/api/mcp/servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"servers\":{}}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(delete("/api/mcp/servers/srv"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(put("/api/mcp/servers/srv/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/mcp/servers/srv/auth"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/mcp/catalog/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"srv\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void oauthCancelIsIdempotentForMissingFlows() throws Exception {
        mockMvc.perform(delete("/api/mcp/oauth/flows/flow-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.status").value("expired"));
    }

    @Test
    void oauthCallbackReturnsHermesExpiredHtmlForMissingFlows() throws Exception {
        mockMvc.perform(get("/api/mcp/oauth/callback/filesystem")
                .param("code", "abc")
                .param("state", "state-1"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("OAuth flow expired")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Return to Hermes and try again.")));
    }
}
