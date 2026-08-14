package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CapabilitiesControllerTest {

    private MockMvc mockMvc;
    private AgentProperties properties;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("test-model");
        when(properties.getModel()).thenReturn(modelProps);

        AgentProperties.SecurityProperties secProps = new AgentProperties.SecurityProperties();
        when(properties.getSecurity()).thenReturn(secProps);

        AgentProperties.CronProperties cronProps = new AgentProperties.CronProperties();
        when(properties.getCron()).thenReturn(cronProps);

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getModelOverride()).thenReturn(null);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "file", "terminal"));
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.listSkillNames()).thenReturn(List.of("skill1", "skill2"));

        TtsService ttsService = mock(TtsService.class);
        TranscriptionService transcriptionService = mock(TranscriptionService.class);

        mockMvc = MockMvcBuilders.standaloneSetup(new CapabilitiesController(
            properties, toolRegistry, skillManager, runtimeConfigService,
            ttsService, transcriptionService)).build();
    }

    @Test
    void capabilitiesReturnsCorrectStructure() throws Exception {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("java-agent.api_server.capabilities"))
            .andExpect(jsonPath("$.platform").value("java-agent"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.auth.type").value("bearer"))
            .andExpect(jsonPath("$.auth.required").value(false))
            .andExpect(jsonPath("$.features.chat_completions").value(true))
            .andExpect(jsonPath("$.features.session_chat").value(true))
            .andExpect(jsonPath("$.features.responses_api").value(false))
            .andExpect(jsonPath("$.endpoints.models.path").value("/v1/models"))
            .andExpect(jsonPath("$.endpoints.chat_completions.path").value("/v1/chat/completions"))
            .andExpect(jsonPath("$.endpoints.sessions.path").value("/api/v2/sessions"))
            .andExpect(jsonPath("$.toolsets").isArray())
            .andExpect(jsonPath("$.skills_count").value(2));
    }

    @Test
    void authRequiredWhenApiKeySet() throws Exception {
        AgentProperties.SecurityProperties secProps = new AgentProperties.SecurityProperties();
        secProps.setApiKey("secret-key");
        when(properties.getSecurity()).thenReturn(secProps);

        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(jsonPath("$.auth.required").value(true));
    }

    @Test
    void directCallReturnsMap() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(Set.of("core"));
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.listSkillNames()).thenReturn(List.of());

        var controller = new CapabilitiesController(
            properties, toolRegistry, skillManager, runtimeConfigService,
            null, null);
        var result = controller.capabilities();
        assertThat(result).containsEntry("platform", "java-agent");
        assertThat(result).containsKey("features");
        assertThat(result).containsKey("endpoints");
    }
}