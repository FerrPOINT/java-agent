package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.service.McpConfigStore;
import com.azhukov.agent.service.McpOAuthFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.quality.Strictness.LENIENT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
class McpDashboardControllerBranchTest {

    private MockMvc mockMvc;

    @Mock
    private McpLifecycleManager mcpLifecycleManager;

    @Mock
    private McpConfigStore configStore;

    @Mock
    private McpOAuthFlowService oauthFlows;

    @Mock
    private ObjectProvider<McpConfigStore> configStoreProvider;

    @Mock
    private ObjectProvider<McpOAuthFlowService> oauthFlowProvider;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        when(configStoreProvider.getIfAvailable()).thenReturn(configStore);
        when(oauthFlowProvider.getIfAvailable()).thenReturn(oauthFlows);
        mockMvc = MockMvcBuilders.standaloneSetup(new McpDashboardController(
                mcpLifecycleManager, properties, new ObjectMapper(),
                configStoreProvider, oauthFlowProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static McpServerConfigEntity savedEntity() {
        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setName("filesystem");
        entity.setTransport("stdio");
        entity.setCommand("npx");
        return entity;
    }

    private static Map<String, Object> serverBody() {
        return Map.of(
            "name", "filesystem",
            "command", "npx",
            "args", List.of("-y", "@modelcontextprotocol/server-filesystem"),
            "env", Map.of("API_KEY", "sk-x")
        );
    }

    @Test
    void addServerPersistsValidatedConfig() throws Exception {
        when(configStore.upsert(any(), any(McpConfigStore.ServerConfigInput.class)))
            .thenReturn(savedEntity());
        when(configStore.redactedView(any())).thenReturn(Map.of("name", "filesystem", "enabled", true));

        mockMvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(serverBody())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("filesystem"));

        verify(configStore).upsert(any(), any(McpConfigStore.ServerConfigInput.class));
    }

    @Test
    void addServerRejectsInvalidPayloadWith400() throws Exception {
        when(configStore.upsert(any(), any(McpConfigStore.ServerConfigInput.class)))
            .thenThrow(new IllegalArgumentException("name must match [a-z0-9-]+"));

        mockMvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(serverBody())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("name must match [a-z0-9-]+"));
    }

    @Test
    void addServerWithoutStoreReturns501() throws Exception {
        when(configStoreProvider.getIfAvailable()).thenReturn(null);

        mockMvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(serverBody())))
            .andExpect(status().isNotImplemented());

        verify(configStore, never()).upsert(any(), any());
    }

    @Test
    void replaceServersUpsertsEachEntryAndReturnsList() throws Exception {
        when(configStore.upsert(any(), any(McpConfigStore.ServerConfigInput.class)))
            .thenReturn(savedEntity());
        when(configStore.redactedView(any())).thenReturn(Map.of("name", "filesystem", "enabled", true));

        mockMvc.perform(put("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("servers", List.of(serverBody())))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.servers[0].name").value("filesystem"));

        verify(configStore).upsert(any(), any(McpConfigStore.ServerConfigInput.class));
    }

    @Test
    void replaceServersRejectsEmptyList() throws Exception {
        mockMvc.perform(put("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"servers\": []}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("servers list is required"));
    }

    @Test
    void replaceServersRejectsNonMappingEntry() throws Exception {
        mockMvc.perform(put("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"servers\": [\"not-a-map\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("each server entry must be a mapping"));
    }

    @Test
    void removeServerDeletesConfigAndSchemaCache() throws Exception {
        when(configStore.delete(null, "filesystem")).thenReturn(true);

        mockMvc.perform(delete("/api/mcp/servers/filesystem"))
            .andExpect(status().isOk());

        verify(configStore).delete(null, "filesystem");
    }

    @Test
    void removeServerUnknownNameReturns404() throws Exception {
        when(configStore.delete(null, "missing")).thenReturn(false);

        mockMvc.perform(delete("/api/mcp/servers/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void setEnabledTogglesConfiguredServer() throws Exception {
        when(configStore.setEnabled(null, "filesystem", true)).thenReturn(Optional.of(savedEntity()));
        when(configStore.redactedView(any())).thenReturn(Map.of("name", "filesystem", "enabled", true));

        mockMvc.perform(put("/api/mcp/servers/filesystem/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("filesystem"));

        verify(configStore).setEnabled(null, "filesystem", true);
    }

    @Test
    void setEnabledUnknownServerReturns404() throws Exception {
        when(configStore.setEnabled(null, "missing", true)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/mcp/servers/missing/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": true}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void startAuthFlowCreatesFlowAndReturnsUrl() throws Exception {
        when(oauthFlows.start(eq(null), eq("filesystem"), any()))
            .thenReturn(new McpOAuthFlowService.FlowStart("flow-1", "https://auth.example/oauth", "600"));

        mockMvc.perform(post("/api/mcp/servers/filesystem/auth"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flow_id").value("flow-1"))
            .andExpect(jsonPath("$.authorization_url").value("https://auth.example/oauth"));
    }

    @Test
    void pollFlowReturnsPendingStatus() throws Exception {
        McpOAuthFlowEntity flow = new McpOAuthFlowEntity();
        flow.setServerName("filesystem");
        flow.setStatus("pending");
        flow.setExpiresAt(java.time.Instant.now().plusSeconds(60));
        when(oauthFlows.status("flow-1")).thenReturn(Optional.of(flow));

        mockMvc.perform(get("/api/mcp/oauth/flows/flow-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.server").value("filesystem"));
    }

    @Test
    void pollFlowUnknownReturns404() throws Exception {
        when(oauthFlows.status("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mcp/oauth/flows/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteFlowCancelsAndConfirms() throws Exception {
        when(oauthFlows.cancel("flow-1")).thenReturn(true);

        mockMvc.perform(delete("/api/mcp/oauth/flows/flow-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void deleteFlowUnknownReportsExpired() throws Exception {
        when(oauthFlows.cancel("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/mcp/oauth/flows/missing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("expired"));
    }

    @Test
    void oauthCallbackRendersSuccessHtml() throws Exception {
        when(oauthFlows.complete("filesystem", "code-1", "state-1"))
            .thenReturn(Map.of("ok", true));

        mockMvc.perform(get("/api/mcp/oauth/callback/filesystem")
                .param("code", "code-1")
                .param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Authorization complete")));
    }

    @Test
    void oauthCallbackWithProviderErrorRendersFailedHtml() throws Exception {
        mockMvc.perform(get("/api/mcp/oauth/callback/filesystem")
                .param("error", "access_denied"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Authorization failed")));
    }

    @Test
    void catalogInstallPersistsCatalogEntry() throws Exception {
        when(configStore.upsert(any(), any(McpConfigStore.ServerConfigInput.class)))
            .thenReturn(savedEntity());
        when(configStore.redactedView(any())).thenReturn(Map.of("name", "filesystem", "enabled", true));

        mockMvc.perform(post("/api/mcp/catalog/install").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"filesystem\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(configStore).upsert(any(), any(McpConfigStore.ServerConfigInput.class));
    }

    @Test
    void catalogInstallUnknownNameReturns404() throws Exception {
        mockMvc.perform(post("/api/mcp/catalog/install").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"no-such-server\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void catalogInstallMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/api/mcp/catalog/install").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testServerReportsConnectivity() throws Exception {
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of(
            new McpLifecycleManager.DiscoveredTool("filesystem", "read_file", null)));
        AgentProperties.McpProperties.ServerProperties configured = new AgentProperties.McpProperties.ServerProperties();
        configured.setName("filesystem");
        properties.getMcp().getServers().add(configured);

        mockMvc.perform(post("/api/mcp/servers/filesystem/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void testServerNotConnectedReportsFailure() throws Exception {
        when(mcpLifecycleManager.listDiscoveredTools()).thenReturn(List.of());
        AgentProperties.McpProperties.ServerProperties configured = new AgentProperties.McpProperties.ServerProperties();
        configured.setName("missing");
        properties.getMcp().getServers().add(configured);

        mockMvc.perform(post("/api/mcp/servers/missing/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }
}
