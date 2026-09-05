package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelsControllerTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;
    private AgentProperties properties;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("test-model");
        when(properties.getModel()).thenReturn(modelProps);

        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        when(properties.getApi()).thenReturn(apiProps);

        AgentProperties profileProperties = new AgentProperties();
        profileProperties.getProfile().setName("default");
        profileProperties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        profileProperties.getCore().setSoulMdPath(tempDir.resolve("SOUL.md").toString());
        profileService = new ProfileService(profileProperties, new RuntimeConfigService());

        mockMvc = MockMvcBuilders.standaloneSetup(new ModelsController(properties, profileService)).build();
    }

    @Test
    void listModelsReturnsOpenAiFormat() throws Exception {
        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("java-agent"))
            .andExpect(jsonPath("$.data[0].object").value("model"))
            .andExpect(jsonPath("$.data[0].owned_by").value("java-agent"))
            .andExpect(jsonPath("$.data[0].root").value("java-agent"));
    }

    @Test
    void profilePrefixedModelsRouteAdvertisesProfileName() throws Exception {
        createProfile("work");

        mockMvc.perform(get("/p/work/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].id").value("work"))
            .andExpect(jsonPath("$.data[0].root").value("work"));
    }

    @Test
    void profilePrefixedModelsRouteUsesProfileApiServerModelNameWhenSet() throws Exception {
        createProfile("work");
        profileService.writeConfig("work", Map.of(
            "platforms", Map.of(
                "api_server", Map.of(
                    "extra", Map.of("model_name", "lucas")))));

        mockMvc.perform(get("/p/work/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("lucas"))
            .andExpect(jsonPath("$.data[0].root").value("lucas"));
    }

    @Test
    void profilePrefixedModelsRouteRejectsUnknownProfile() throws Exception {
        mockMvc.perform(get("/p/ghost/v1/models"))
            .andExpect(status().isNotFound());
    }

    @Test
    void apiModelNameTakesPrecedence() throws Exception {
        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        apiProps.setModelName("custom-agent");
        when(properties.getApi()).thenReturn(apiProps);

        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("custom-agent"));
    }

    @Test
    void modelRoutesAreAdvertisedAsAliases() throws Exception {
        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setApiKey("sk-route-secret");
        apiProps.getModelRoutes().put("fast-agent", route);
        when(properties.getApi()).thenReturn(apiProps);

        MvcResult result = mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[1].id").value("fast-agent"))
            .andExpect(jsonPath("$.data[1].root").value("openrouter/fast-model"))
            .andExpect(jsonPath("$.data[1].parent").value("java-agent"))
            .andExpect(jsonPath("$.data[1].apiKey").doesNotExist())
            .andExpect(jsonPath("$.data[1].api_key").doesNotExist())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("sk-route-secret");
    }

    @Test
    void blankApiModelNameKeepsHermesVirtualModel() throws Exception {
        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        apiProps.setModelName(" ");
        when(properties.getApi()).thenReturn(apiProps);

        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("java-agent"))
            .andExpect(jsonPath("$.data[0].root").value("java-agent"));
    }

    @Test
    void blankApiModelNameStillRoutesHermesVirtualModelToConfiguredProviderModel() {
        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        apiProps.setModelName(" ");
        when(properties.getApi()).thenReturn(apiProps);

        assertThat(OpenAiModelRouting.runtimeModelName(properties, "java-agent"))
            .isEqualTo("test-model");
    }

    @Test
    void directCallReturnsMap() {
        var controller = new ModelsController(properties);
        var result = controller.listModels();
        assertThat(result).containsEntry("object", "list");
        @SuppressWarnings("unchecked")
        var data = (List<?>) result.get("data");
        assertThat(data).hasSize(1);
    }

    private void createProfile(String name) throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            name, null, false, false, true, null, null, null, null));
    }
}
