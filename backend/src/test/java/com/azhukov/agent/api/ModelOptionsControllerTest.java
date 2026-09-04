package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelOptionsControllerTest {

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private ProfileService profileService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties model = new AgentProperties.ModelProperties();
        model.setProvider("openai-compatible");
        model.setModelName("gpt-5");
        model.setBaseUrl("https://models.example/v1");
        model.setMaxTokens(8192);
        when(properties.getModel()).thenReturn(model);

        AgentProperties.ContextProperties context = new AgentProperties.ContextProperties();
        context.setMaxTokens(128000);
        when(properties.getContext()).thenReturn(context);

        AgentProperties.VisionProperties vision = new AgentProperties.VisionProperties();
        vision.setProvider("gemini");
        vision.setModelName("gemini-2.5-pro");
        vision.setBaseUrl("https://vision.example/v1");
        when(properties.getVision()).thenReturn(vision);

        AgentProperties.AuxiliaryProperties auxiliary = new AgentProperties.AuxiliaryProperties();
        auxiliary.setEnabled(true);
        auxiliary.setProvider("anthropic");
        auxiliary.setModelName("claude-haiku-4-5");
        auxiliary.setBaseUrl("https://aux.example/v1");
        when(properties.getAuxiliary()).thenReturn(auxiliary);

        AgentProperties.ApiProperties api = new AgentProperties.ApiProperties();
        when(properties.getApi()).thenReturn(api);

        runtimeConfigService = new RuntimeConfigService();
        AgentProperties profileProperties = new AgentProperties();
        profileProperties.getProfile().setName("default");
        profileProperties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        profileProperties.getCore().setSoulMdPath(tempDir.resolve("SOUL.md").toString());
        profileService = new ProfileService(profileProperties, runtimeConfigService);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new ModelOptionsController(properties, runtimeConfigService, profileService)).build();
    }

    @Test
    void returnsHermesModelOptionsShapeFromPrimaryAndFallbackChain() throws Exception {
        FallbackConfig fallback = new FallbackConfig();
        fallback.setProvider("anthropic");
        fallback.setModel("claude-sonnet-4-5");
        fallback.setBaseUrl("https://anthropic.example/v1");
        when(properties.getFallbackChain()).thenReturn(List.of(fallback));

        mockMvc.perform(get("/api/model/options?refresh=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("gpt-5"))
            .andExpect(jsonPath("$.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.providers.length()").value(org.hamcrest.Matchers.greaterThan(2)))
            .andExpect(jsonPath("$.providers[0].slug").value("openai-compatible"))
            .andExpect(jsonPath("$.providers[0].label").value("OpenAI Compatible"))
            .andExpect(jsonPath("$.providers[0].models[0]").value("gpt-5"))
            .andExpect(jsonPath("$.providers[0].is_current").value(true))
            .andExpect(jsonPath("$.providers[0].current").value(true))
            .andExpect(jsonPath("$.providers[0].authenticated").value(true))
            .andExpect(jsonPath("$.providers[0].is_user_defined").value(true))
            .andExpect(jsonPath("$.providers[0].source").value("user-config"))
            .andExpect(jsonPath("$.providers[0].capabilities['gpt-5'].reasoning").value(true))
            .andExpect(jsonPath("$.providers[0].featured_models").isArray())
            .andExpect(jsonPath("$.providers[0].api_url").value("https://models.example/v1"))
            .andExpect(jsonPath("$.providers[1].slug").value("anthropic"))
            .andExpect(jsonPath("$.providers[1].models[0]").value("claude-sonnet-4-5"));
    }

    @Test
    void providerRowsExposeKeysReadByHermesModelPicker() throws Exception {
        when(properties.getFallbackChain()).thenReturn(List.of());

        mockMvc.perform(get("/api/model/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].name").exists())
            .andExpect(jsonPath("$.providers[0].slug").exists())
            .andExpect(jsonPath("$.providers[0].models").isArray())
            .andExpect(jsonPath("$.providers[0].total_models").value(1))
            .andExpect(jsonPath("$.providers[0].is_current").value(true))
            .andExpect(jsonPath("$.providers[0].authenticated").value(true))
            .andExpect(jsonPath("$.providers[0].capabilities").exists())
            .andExpect(jsonPath("$.providers[0].featured_models").isArray());
    }

    @Test
    void profilePrefixedModelRoutesReadNamedProfileConfig() throws Exception {
        when(properties.getFallbackChain()).thenReturn(List.of());
        createProfile("work");
        profileService.writeModel("work", "openrouter", "worker/model", "https://worker.example/v1");

        mockMvc.perform(get("/p/work/api/model/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("worker/model"))
            .andExpect(jsonPath("$.provider").value("openrouter"));

        mockMvc.perform(get("/p/work/api/model/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("worker/model"))
            .andExpect(jsonPath("$.provider").value("openrouter"));
    }

    @Test
    void groupsFallbackModelsByProviderWithoutDuplicatingPrimary() throws Exception {
        FallbackConfig duplicate = new FallbackConfig();
        duplicate.setProvider("openai-compatible");
        duplicate.setModel("gpt-5");
        FallbackConfig second = new FallbackConfig();
        second.setProvider("openai-compatible");
        second.setModel("gpt-5-mini");
        when(properties.getFallbackChain()).thenReturn(List.of(duplicate, second));

        mockMvc.perform(get("/api/model/options?include_unconfigured=false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers.length()").value(1))
            .andExpect(jsonPath("$.providers[0].models.length()").value(2))
            .andExpect(jsonPath("$.providers[0].models[0]").value("gpt-5"))
            .andExpect(jsonPath("$.providers[0].models[1]").value("gpt-5-mini"));
    }

    @Test
    void includeUnconfiguredAddsHermesCanonicalSkeletonRows() throws Exception {
        when(properties.getFallbackChain()).thenReturn(List.of());

        mockMvc.perform(get("/api/model/options?explicit_only=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].slug").value("openai-compatible"))
            .andExpect(jsonPath("$.providers[1].slug").value("nous"))
            .andExpect(jsonPath("$.providers[1].label").value("Nous Portal"))
            .andExpect(jsonPath("$.providers[1].source").value("canonical"))
            .andExpect(jsonPath("$.providers[1].authenticated").value(false))
            .andExpect(jsonPath("$.providers[1].auth_type").value("api_key"))
            .andExpect(jsonPath("$.providers[1].models.length()").value(0))
            .andExpect(jsonPath("$.providers[1].capabilities").isMap())
            .andExpect(jsonPath("$.providers[1].featured_models").isArray());
    }

    @Test
    void recommendedDefaultReturnsFirstKnownModelForProvider() throws Exception {
        FallbackConfig fallback = new FallbackConfig();
        fallback.setProvider("anthropic");
        fallback.setModel("claude-sonnet-4-5");
        fallback.setBaseUrl("https://anthropic.example/v1");
        when(properties.getFallbackChain()).thenReturn(List.of(fallback));

        mockMvc.perform(get("/api/model/recommended-default?provider=anthropic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("anthropic"))
            .andExpect(jsonPath("$.model").value("claude-sonnet-4-5"))
            .andExpect(jsonPath("$.free_tier").hasJsonPath())
            .andExpect(jsonPath("$.free_tier").isEmpty());
    }

    @Test
    void recommendedDefaultFallsBackToCurrentProviderWhenProviderBlank() throws Exception {
        when(properties.getFallbackChain()).thenReturn(List.of());

        mockMvc.perform(get("/api/model/recommended-default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.model").value("gpt-5"));
    }

    @Test
    void infoReturnsResolvedModelMetadataInHermesShape() throws Exception {
        mockMvc.perform(get("/api/model/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("gpt-5"))
            .andExpect(jsonPath("$.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.auto_context_length").value(0))
            .andExpect(jsonPath("$.config_context_length").value(128000))
            .andExpect(jsonPath("$.effective_context_length").value(128000))
            .andExpect(jsonPath("$.capabilities.supports_tools").value(true))
            .andExpect(jsonPath("$.capabilities.supports_reasoning").value(true))
            .andExpect(jsonPath("$.capabilities.supports_vision").value(false))
            .andExpect(jsonPath("$.capabilities.context_window").value(128000))
            .andExpect(jsonPath("$.capabilities.max_output_tokens").value(8192));
    }

    @Test
    void auxiliaryReturnsHermesTaskSlotsWithJavaAssignments() throws Exception {
        mockMvc.perform(get("/api/model/auxiliary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.main.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.main.model").value("gpt-5"))
            .andExpect(jsonPath("$.tasks.length()").value(11))
            .andExpect(jsonPath("$.tasks[0].task").value("vision"))
            .andExpect(jsonPath("$.tasks[0].provider").value("gemini"))
            .andExpect(jsonPath("$.tasks[0].model").value("gemini-2.5-pro"))
            .andExpect(jsonPath("$.tasks[0].base_url").value("https://vision.example/v1"))
            .andExpect(jsonPath("$.tasks[1].task").value("compression"))
            .andExpect(jsonPath("$.tasks[1].provider").value("anthropic"))
            .andExpect(jsonPath("$.tasks[1].model").value("claude-haiku-4-5"))
            .andExpect(jsonPath("$.tasks[1].base_url").value("https://aux.example/v1"));
    }

    @Test
    void fallsBackToApiModelAliasWhenProviderModelIsBlank() {
        AgentProperties.ModelProperties model = new AgentProperties.ModelProperties();
        model.setProvider("openai-compatible");
        model.setModelName(" ");
        when(properties.getModel()).thenReturn(model);

        var result = new ModelOptionsController(properties, runtimeConfigService).options();

        assertThat(result).containsEntry("model", "java-agent");
        assertThat(result).containsEntry("provider", "openai-compatible");
    }

    @Test
    void setModelAssignmentStoresRuntimeMainSelectionLikeHermesDashboard() throws Exception {
        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "main",
                      "provider": "openrouter",
                      "model": "anthropic/claude-sonnet-4-5",
                      "base_url": "https://openrouter.example/api/v1",
                      "api_key": "secret"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.scope").value("main"))
            .andExpect(jsonPath("$.provider").value("openrouter"))
            .andExpect(jsonPath("$.model").value("anthropic/claude-sonnet-4-5"))
            .andExpect(jsonPath("$.base_url").value("https://openrouter.example/api/v1"))
            .andExpect(jsonPath("$.gateway_tools").isArray())
            .andExpect(jsonPath("$.stale_aux").isArray())
            .andExpect(jsonPath("$.cron_model_impact.available").value(false));

        assertThat(runtimeConfigService.getModelSelection()).isNotNull();
        assertThat(runtimeConfigService.getModelSelection().model()).isEqualTo("anthropic/claude-sonnet-4-5");
        assertThat(runtimeConfigService.getModelSelection().provider()).isEqualTo("openrouter");
        assertThat(runtimeConfigService.getModelSelection().apiKey()).isEqualTo("secret");

        mockMvc.perform(get("/api/model/options?include_unconfigured=false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("anthropic/claude-sonnet-4-5"))
            .andExpect(jsonPath("$.provider").value("openrouter"))
            .andExpect(jsonPath("$.providers[0].slug").value("openrouter"))
            .andExpect(jsonPath("$.providers[0].api_url").value("https://openrouter.example/api/v1"));
    }

    @Test
    void profileModelInfoRejectsUnknownProfileInsteadOfReturningGlobalState() throws Exception {
        mockMvc.perform(get("/api/model/info?profile=ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    void setModelAssignmentStoresMainSelectionInNamedProfileConfigOnly() throws Exception {
        createProfile("worker_beta");

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "main",
                      "provider": "openrouter",
                      "model": "test/model-1",
                      "base_url": "https://openrouter.example/api/v1",
                      "api_key": "profile-secret",
                      "confirm_expensive_model": true,
                      "profile": "worker_beta"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.scope").value("main"))
            .andExpect(jsonPath("$.provider").value("openrouter"))
            .andExpect(jsonPath("$.model").value("test/model-1"))
            .andExpect(jsonPath("$.base_url").value("https://openrouter.example/api/v1"))
            .andExpect(jsonPath("$.cron_model_impact.available").value(false));

        assertThat(runtimeConfigService.getModelSelection()).isNull();
        Map<String, Object> model = modelConfig("worker_beta");
        assertThat(model).containsEntry("provider", "openrouter");
        assertThat(model).containsEntry("default", "test/model-1");
        assertThat(model).containsEntry("base_url", "https://openrouter.example/api/v1");
        assertThat(model).containsEntry("api_key", "profile-secret");
        assertThat(modelConfig("default")).doesNotContainEntry("default", "test/model-1");
    }

    @Test
    void mainAssignmentReportsOnlyTargetProfileCronImpact() throws Exception {
        createProfile("worker_beta");
        CronJobEntity workerJob = cronJob("Worker summary", "worker_beta");
        workerJob.setProviderSnapshot("openrouter");
        workerJob.setModelSnapshot("old/model");
        CronJobEntity defaultJob = cronJob("Default summary", "default");
        defaultJob.setProviderSnapshot("openrouter");
        defaultJob.setModelSnapshot("old/model");

        CronJobService cronJobService = mock(CronJobService.class);
        when(cronJobService.listForProfile("worker_beta", true)).thenReturn(List.of(workerJob));
        when(cronJobService.listForProfile("default", true)).thenReturn(List.of(defaultJob));
        MockMvc scopedMvc = mockMvcWithCron(cronJobService);

        scopedMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "main",
                      "provider": "nous",
                      "model": "new/model",
                      "confirm_expensive_model": true,
                      "profile": "worker_beta"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cron_model_impact.available").value(true))
            .andExpect(jsonPath("$.cron_model_impact.guard_enabled").value(true))
            .andExpect(jsonPath("$.cron_model_impact.affected_count").value(1))
            .andExpect(jsonPath("$.cron_model_impact.truncated").value(false))
            .andExpect(jsonPath("$.cron_model_impact.jobs[0].id").value("111111111111"))
            .andExpect(jsonPath("$.cron_model_impact.jobs[0].name").value("Worker summary"))
            .andExpect(jsonPath("$.cron_model_impact.jobs[0].drifted_axes[0]").value("provider"))
            .andExpect(jsonPath("$.cron_model_impact.jobs[0].drifted_axes[1]").value("model"));

        org.mockito.Mockito.verify(cronJobService).listForProfile("worker_beta", true);
        org.mockito.Mockito.verify(cronJobService, org.mockito.Mockito.never()).listForProfile("default", true);
    }

    @Test
    void setModelAssignmentStoresAuxiliarySelectionInNamedProfileConfigOnly() throws Exception {
        createProfile("worker_beta");

        mockMvc.perform(post("/p/worker_beta/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "auxiliary",
                      "task": "vision",
                      "provider": "gemini",
                      "model": "gemini-2.5-pro",
                      "base_url": "https://vision.example/v1",
                      "api_key": "vision-secret"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.scope").value("auxiliary"))
            .andExpect(jsonPath("$.tasks[0]").value("vision"))
            .andExpect(jsonPath("$.provider").value("gemini"))
            .andExpect(jsonPath("$.model").value("gemini-2.5-pro"))
            .andExpect(jsonPath("$.cron_model_impact").doesNotExist());

        mockMvc.perform(get("/api/model/auxiliary?profile=worker_beta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tasks[0].task").value("vision"))
            .andExpect(jsonPath("$.tasks[0].provider").value("gemini"))
            .andExpect(jsonPath("$.tasks[0].model").value("gemini-2.5-pro"))
            .andExpect(jsonPath("$.tasks[0].base_url").value("https://vision.example/v1"));

        Map<String, Object> auxiliary = auxiliaryConfig("worker_beta");
        assertThat(auxiliary).containsKey("vision");
        @SuppressWarnings("unchecked")
        Map<String, Object> vision = (Map<String, Object>) auxiliary.get("vision");
        assertThat(vision)
            .containsEntry("provider", "gemini")
            .containsEntry("model", "gemini-2.5-pro")
            .containsEntry("base_url", "https://vision.example/v1")
            .containsEntry("api_key", "vision-secret");

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "auxiliary",
                      "task": "__reset__",
                      "profile": "worker_beta"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.reset").value(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> resetVision = (Map<String, Object>) auxiliaryConfig("worker_beta").get("vision");
        assertThat(resetVision)
            .containsEntry("provider", "auto")
            .containsEntry("model", "")
            .doesNotContainKey("base_url")
            .doesNotContainKey("api_key");
    }

    @Test
    void modelProfilePathQueryAndBodyMustMatch() throws Exception {
        createProfile("work");

        mockMvc.perform(post("/p/work/api/model/set?profile=default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "main",
                      "provider": "openrouter",
                      "model": "test/model-1"
                    }
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/model/set?profile=work")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "main",
                      "provider": "openrouter",
                      "model": "test/model-1",
                      "profile": "default"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setModelAssignmentRejectsInvalidOrUnsupportedScopes() throws Exception {
        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"bad\",\"provider\":\"openrouter\",\"model\":\"gpt-5\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"main\",\"model\":\"gpt-5\"}"))
            .andExpect(status().isBadRequest());
        assertThat(runtimeConfigService.getModelSelection()).isNull();

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"auxiliary\",\"model\":\"gpt-5\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"auxiliary\",\"provider\":\"openrouter\",\"task\":\"unknown\",\"model\":\"gpt-5\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/model/set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"auxiliary\",\"provider\":\"openrouter\",\"model\":\"gpt-5\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void moaRoutesExposeDisabledReadShapeAndRejectWrites() throws Exception {
        mockMvc.perform(get("/api/model/moa"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.default_preset").value("default"))
            .andExpect(jsonPath("$.presets.default.enabled").value(false))
            .andExpect(jsonPath("$.reference_models").isArray());

        mockMvc.perform(put("/api/model/moa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotImplemented());
    }

    private void createProfile(String name) throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            name, null, false, false, true, null, null, null, null));
    }

    private MockMvc mockMvcWithCron(CronJobService cronJobService) {
        return MockMvcBuilders.standaloneSetup(
            new ModelOptionsController(properties, runtimeConfigService, profileService, cronJobService)).build();
    }

    private static CronJobEntity cronJob(String name, String profile) {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        job.setName(name);
        job.setProfile(profile);
        job.setEnabled(true);
        job.setNoAgent(false);
        return job;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> modelConfig(String profile) throws Exception {
        Object value = profileService.readConfig(profile).get("model");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auxiliaryConfig(String profile) throws Exception {
        Object value = profileService.readConfig(profile).get("auxiliary");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
