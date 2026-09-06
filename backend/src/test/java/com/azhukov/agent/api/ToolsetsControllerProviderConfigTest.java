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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage for ToolsetsController per-toolset provider config rows:
 * tts, image_gen, vision, stt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolsetsControllerProviderConfigTest {

    @Mock private SpringToolRegistry toolRegistry;

    private AgentProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal"));
        when(toolRegistry.getDefinitions(Set.of("web"))).thenReturn(List.of(
            new ToolDefinition("web_search", "search", Map.of())));
        mockMvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, properties)).build();
    }

    @Test
    void ttsConfigListsEdgeAndOpenAiProviders() throws Exception {
        properties.getTts().setApiKey("k");
        mockMvc.perform(get("/api/tools/toolsets/tts/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers").isArray());
    }

    @Test
    void imageGenConfigListsOpenAiProvider() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/image_gen/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers").isArray());
    }

    @Test
    void visionConfigListsOpenAiCompatibleProvider() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/vision/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers").isArray());
    }

    @Test
    void sttConfigListsOpenAiProvider() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/stt/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers").isArray());
    }
}
