package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch coverage for ToolsetsController mutations: toggle validation,
 * model-catalog rejection, provider selection validation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ToolsetsControllerBranchTest {

    private MockMvc mockMvc;
    private AgentProperties properties;

    @Mock
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getSkills().setDefaultToolsets(List.of("hermes-cli"));
        properties.getApi().setChatCompletionToolsets(List.of("hermes-api-server"));

        ToolDefinition webSearch = new ToolDefinition("web_search", "Search the web", Map.of());
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal", "hermes-cli", "hermes-api-server"));
        when(toolRegistry.getDefinitions(Set.of("web"))).thenReturn(List.of(webSearch));
        when(toolRegistry.getDefinitions(Set.of("terminal"))).thenReturn(List.of(webSearch));
        when(toolRegistry.getDefinitions(Set.of("hermes-cli"))).thenReturn(List.of(webSearch));
        when(toolRegistry.getDefinitions(Set.of("hermes-api-server"))).thenReturn(List.of(webSearch));

        mockMvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, properties)).build();
    }

    @Test
    void toggleRejectsMissingEnabled() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("enabled is required"));
    }

    @Test
    void toggleUnknownToolsetReturns400() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/telepathy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown toolset: telepathy"));
    }

    @Test
    void toggleKnownToolsetEnablesAndDisables() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("web"));

        mockMvc.perform(put("/api/tools/toolsets/web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void selectModelRejectsMissingModel() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("model is required"));
    }

    @Test
    void selectModelAlwaysRejectedNoCatalog() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"gpt-4o\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Toolset has no model catalog: web"));
    }

    @Test
    void selectProviderRejectsUnknownToolset() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/telepathy/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown toolset: telepathy"));
    }

    @Test
    void selectProviderRejectsMissingProvider() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("provider is required"));
    }

    @Test
    void selectProviderRejectsCapabilityOnNonWebToolset() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/terminal/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"x\",\"capability\":\"search\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("capability selection is only supported for the web toolset"));
    }

    @Test
    void selectProviderRejectsUnknownCapability() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"x\",\"capability\":\"summarize\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown capability: summarize (expected 'search' or 'extract')"));
    }

    @Test
    void selectProviderRejectsExtractCapability() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"x\",\"capability\":\"extract\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.supported[0]").value("builtin"));
    }

    @Test
    void selectProviderRejectsUnknownProvider() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"no-such-provider\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Unknown provider")));
    }

    @Test
    void listToolsetsByPlatformDashboard() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets"))
            .andExpect(status().isOk());
    }
}
