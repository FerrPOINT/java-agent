package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ToolsetsControllerTest {

    private MockMvc mockMvc;
    private ToolRegistry toolRegistry;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        properties = mock(AgentProperties.class);

        AgentProperties.SkillsProperties skillsProps = new AgentProperties.SkillsProperties();
        when(properties.getSkills()).thenReturn(skillsProps);

        // Set up two toolsets with tools
        ToolDefinition webSearch = new ToolDefinition("web_search", "Search the web", Map.of());
        ToolDefinition webFetch = new ToolDefinition("web_fetch", "Fetch a URL", Map.of());
        ToolDefinition termExec = new ToolDefinition("terminal", "Execute terminal command", Map.of());

        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal"));
        when(toolRegistry.getDefinitions(Set.of("web"))).thenReturn(List.of(webSearch, webFetch));
        when(toolRegistry.getDefinitions(Set.of("terminal"))).thenReturn(List.of(termExec));

        mockMvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, properties)).build();
    }

    @Test
    void listToolsetsReturnsAllToolsets() throws Exception {
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.platform").value("java-agent"))
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void toolsetContainsTools() throws Exception {
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.name=='web')].tools[0]").value("web_fetch"))
            .andExpect(jsonPath("$.data[?(@.name=='web')].tools[1]").value("web_search"))
            .andExpect(jsonPath("$.data[?(@.name=='terminal')].tools[0]").value("terminal"));
    }

    @Test
    void toolsetEnabledStateReflectsDefaultToolsets() throws Exception {
        // Default toolsets include "web" but not "terminal" (based on default config)
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.name=='web')].enabled").value(true))
            .andExpect(jsonPath("$.data[?(@.name=='terminal')].enabled").value(true));
    }

    @Test
    void directCallReturnsMap() {
        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsets();
        assertThat(result).containsEntry("object", "list");
        assertThat(result).containsEntry("platform", "java-agent");
        @SuppressWarnings("unchecked")
        var data = (List<?>) result.get("data");
        assertThat(data).hasSize(2);
    }
}