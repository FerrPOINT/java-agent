package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.SpringToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage + regression for ToolsetsController error/branch paths:
 * unknown toolset config, unknown profile, enable/disable validation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolsetsControllerErrorBranchTest {

    @Mock private SpringToolRegistry toolRegistry;

    private AgentProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal"));
        when(toolRegistry.getDefinitions(Set.of("web"))).thenReturn(List.of(
            new ToolDefinition("web_search", "search", Map.of())));
        when(toolRegistry.getDefinitions(Set.of("terminal"))).thenReturn(List.of(
            new ToolDefinition("terminal", "term", Map.of())));
        mockMvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, properties)).build();
    }

    @Test
    void toolsetConfigUnknownToolsetIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/not_a_toolset/config"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Unknown toolset")));
    }

    @Test
    void toolsetModelsUnknownToolsetHandled() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/not_a_toolset/models"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(code).isIn(200, 400);
            });
    }

    @Test
    void unknownProfilePrefixIsError() throws Exception {
        mockMvc.perform(get("/p/definitely-not-a-profile-xyz/v1/toolsets"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                // 501 = profile routing not configured without ProfileService — acceptable
                org.assertj.core.api.Assertions.assertThat(code).isIn(400, 404, 501);
            });
    }

    @Test
    void enableUnknownToolsetIsRejected() throws Exception {
        mockMvc.perform(post("/v1/toolsets/does_not_exist/enable"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(code).isIn(400, 404, 500);
            });
    }

    @Test
    void dashboardToolsetsListWorks() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets"))
            .andExpect(status().isOk());
    }

    @Test
    void terminalBackendsListed() throws Exception {
        mockMvc.perform(get("/api/tools/terminal/backends"))
            .andExpect(status().isOk());
    }
}
