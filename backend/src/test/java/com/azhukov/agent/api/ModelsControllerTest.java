package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelsControllerTest {

    private MockMvc mockMvc;
    private AgentProperties properties;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("test-model");
        when(properties.getModel()).thenReturn(modelProps);

        AgentProperties.AuxiliaryProperties auxProps = new AgentProperties.AuxiliaryProperties();
        auxProps.setModelName("aux-model");
        when(properties.getAuxiliary()).thenReturn(auxProps);

        FallbackConfig fb = new FallbackConfig();
        fb.setModel("fallback-model");
        fb.setProvider("openai-compatible");
        when(properties.getFallbackChain()).thenReturn(List.of(fb));

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getModelOverride()).thenReturn(null);

        mockMvc = MockMvcBuilders.standaloneSetup(new ModelsController(properties, runtimeConfigService)).build();
    }

    @Test
    void listModelsReturnsOpenAiFormat() throws Exception {
        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].id").value("test-model"))
            .andExpect(jsonPath("$.data[0].object").value("model"))
            .andExpect(jsonPath("$.data[1].id").value("fallback-model"))
            .andExpect(jsonPath("$.data[2].id").value("aux-model"));
    }

    @Test
    void modelOverrideTakesPrecedence() throws Exception {
        when(runtimeConfigService.getModelOverride()).thenReturn("override-model");
        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("override-model"));
    }

    @Test
    void noDuplicateModels() throws Exception {
        // When fallback model equals primary, it should not be listed twice
        FallbackConfig fb = new FallbackConfig();
        fb.setModel("test-model");
        when(properties.getFallbackChain()).thenReturn(List.of(fb));

        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2)) // test-model + aux-model (no duplicate)
            .andExpect(jsonPath("$.data[0].id").value("test-model"))
            .andExpect(jsonPath("$.data[1].id").value("aux-model"));
    }

    @Test
    void directCallReturnsMap() {
        when(runtimeConfigService.getModelOverride()).thenReturn(null);
        var controller = new ModelsController(properties, runtimeConfigService);
        var result = controller.listModels();
        assertThat(result).containsEntry("object", "list");
        @SuppressWarnings("unchecked")
        var data = (List<?>) result.get("data");
        assertThat(data).hasSize(3);
    }
}