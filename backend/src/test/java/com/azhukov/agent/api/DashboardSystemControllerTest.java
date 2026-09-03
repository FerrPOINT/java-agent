package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardSystemControllerTest {

    private AgentProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private ProfileService profileService;
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("hermes.home", tempDir.toString());
        DashboardSystemController.clearInstallIdCacheForTests();
        properties = new AgentProperties();
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-5");
        properties.getModel().setBaseUrl("https://models.example/v1");
        properties.getModel().setApiKey("secret-model-key");
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("SOUL.md").toString());
        properties.getWeb().setSearxngUrl("http://localhost:8888");
        runtimeConfigService = new RuntimeConfigService();
        profileService = new ProfileService(properties, runtimeConfigService);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new DashboardSystemController(properties, runtimeConfigService, profileService)).build();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("hermes.home");
        DashboardSystemController.clearInstallIdCacheForTests();
    }

    @Test
    void statusLogsAndActionStatusExposeHermesDashboardShape() throws Exception {
        mockMvc.perform(get("/api/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_sessions").value(0))
            .andExpect(jsonPath("$.active_agents").value(0))
            .andExpect(jsonPath("$.auth_required").value(false))
            .andExpect(jsonPath("$.auth_providers").isArray())
            .andExpect(jsonPath("$.auth_flows").isArray())
            .andExpect(jsonPath("$.can_update_hermes").value(false))
            .andExpect(jsonPath("$.components.gateway.status").value("ok"))
            .andExpect(jsonPath("$.components.dashboard.status").value("ok"))
            .andExpect(jsonPath("$.components.storage.status").value("ok"))
            .andExpect(jsonPath("$.components.platforms.configured").value(0))
            .andExpect(jsonPath("$.config_path").value("classpath:application.yml"))
            .andExpect(jsonPath("$.disk.pressure").value("unknown"))
            .andExpect(jsonPath("$.gateway_busy").value(false))
            .andExpect(jsonPath("$.gateway_drainable").value(true))
            .andExpect(jsonPath("$.gateway_mode").value("single"))
            .andExpect(jsonPath("$.gateway_running").value(true))
            .andExpect(jsonPath("$.gateway_state").value("running"))
            .andExpect(jsonPath("$.hermes_home").exists())
            .andExpect(jsonPath("$.install_id").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
            .andExpect(jsonPath("$.memory.pressure").value("unknown"))
            .andExpect(jsonPath("$.nous_session_valid").value("unknown"))
            .andExpect(jsonPath("$.overall").value("ok"))
            .andExpect(jsonPath("$.profiles[0]").value("default"))
            .andExpect(jsonPath("$.restart_drain_timeout").value(0))
            .andExpect(jsonPath("$.version").exists());

        mockMvc.perform(get("/api/logs?lines=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file").value("agent"))
            .andExpect(jsonPath("$.lines").isArray());

        mockMvc.perform(get("/api/logs").param("file", "missing"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown log file: missing"));

        mockMvc.perform(get("/api/actions/hermes-update/status?lines=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("hermes-update"))
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.pid").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.lines").isArray());

        mockMvc.perform(get("/api/actions/missing-action/status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown action: missing-action"));
    }

    @Test
    void statusReadsRequestedProfileHomeAndProfileList() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        Path workHome = tempDir.resolve("profiles").resolve("work").toAbsolutePath().normalize();

        mockMvc.perform(get("/api/status").param("profile", "work"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hermes_home").value(workHome.toString()))
            .andExpect(jsonPath("$.env_path").value(workHome.resolve(".env").toString()))
            .andExpect(jsonPath("$.config_path").value(workHome.resolve("config.yaml").toString()))
            .andExpect(jsonPath("$.profiles[0]").value("default"))
            .andExpect(jsonPath("$.profiles[1]").value("work"));

        mockMvc.perform(get("/p/work/api/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hermes_home").value(workHome.toString()));

        mockMvc.perform(get("/p/work/api/status").param("profile", "default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));

        mockMvc.perform(get("/api/status").param("profile", "ghost"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));
    }

    @Test
    void authPortalAndSshRoutesExposeHermesDesktopFallbacks() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("no auth providers registered"));

        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Unauthorized"));

        mockMvc.perform(post("/api/auth/ws-ticket"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Unauthorized"));

        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("no auth providers registered")));

        mockMvc.perform(get("/auth/login").param("provider", "oidc"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown provider: 'oidc'"));

        mockMvc.perform(get("/auth/native/authorize")
                .param("code_challenge_method", "plain"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("code_challenge_method must be S256"));

        mockMvc.perform(get("/auth/native/authorize")
                .param("code_challenge_method", "S256")
                .param("code_challenge", "challenge")
                .param("redirect_uri", "http://127.0.0.1:35217/callback"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown provider: ''"));

        mockMvc.perform(get("/auth/callback"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Missing PKCE state cookie"));

        mockMvc.perform(post("/auth/password-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"basic\",\"username\":\"u\",\"password\":\"p\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown provider"));

        mockMvc.perform(post("/auth/native/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"code\",\"code_verifier\":\"verifier\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid or expired authorization code."));

        mockMvc.perform(post("/auth/native/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("refresh_token required"));

        mockMvc.perform(post("/auth/native/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"refresh\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("session_expired"))
            .andExpect(jsonPath("$.detail").value("Refresh token expired or invalid; start a new sign-in."));

        mockMvc.perform(post("/auth/logout"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/login"));

        mockMvc.perform(get("/api/ssh/ownership"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("SSH ownership is not active"));

        mockMvc.perform(get("/api/portal"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logged_in").value(false))
            .andExpect(jsonPath("$.portal_url").doesNotExist())
            .andExpect(jsonPath("$.inference_url").doesNotExist())
            .andExpect(jsonPath("$.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.subscription_url").value("https://portal.nousresearch.com/manage-subscription"))
            .andExpect(jsonPath("$.features").isArray());

        mockMvc.perform(get("/api/system/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.os").isString())
            .andExpect(jsonPath("$.arch").isString())
            .andExpect(jsonPath("$.hostname").isString())
            .andExpect(jsonPath("$.python_version").isString())
            .andExpect(jsonPath("$.python_impl").isString())
            .andExpect(jsonPath("$.hermes_version").exists())
            .andExpect(jsonPath("$.cpu_count").isNumber())
            .andExpect(jsonPath("$.memory.total").isNumber())
            .andExpect(jsonPath("$.process.pid").isNumber())
            .andExpect(jsonPath("$.psutil").value(false));
    }

    @Test
    void configDefaultsSchemaAndEnvAreReadableWithoutSecretValues() throws Exception {
        runtimeConfigService.setModelSelection(
            "openrouter",
            "anthropic/claude-sonnet-4-5",
            "https://openrouter.example/api/v1",
            "runtime-secret");

        mockMvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model.provider").value("openrouter"))
            .andExpect(jsonPath("$.model.default").value("anthropic/claude-sonnet-4-5"))
            .andExpect(jsonPath("$.model.base_url").value("https://openrouter.example/api/v1"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-model-key"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("runtime-secret"))));

        mockMvc.perform(get("/api/config/defaults"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model.default").value("gpt-5"));

        mockMvc.perform(get("/api/config/raw"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("model:")))
            .andExpect(jsonPath("$.path").value("classpath:application.yml"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-model-key"))));

        mockMvc.perform(get("/api/config/schema"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fields['model.default'].type").value("string"))
            .andExpect(jsonPath("$.category_order").isArray());

        String envBody = mockMvc.perform(get("/api/env"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.AGENT_MODEL_API_KEY.is_set").value(true))
            .andExpect(jsonPath("$.AGENT_MODEL_API_KEY.is_password").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(envBody).doesNotContain("secret-model-key");
    }

    @Test
    void configAndEnvWritesFailExplicitly() throws Exception {
        mockMvc.perform(put("/api/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"config\":{}}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("dashboard config writes are not implemented in the Java port"));

        mockMvc.perform(put("/api/env")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"OPENAI_API_KEY\",\"value\":\"secret\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(delete("/api/env")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"OPENAI_API_KEY\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/env/reveal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"OPENAI_API_KEY\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(put("/api/config/raw")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yaml_text\":\"model: {}\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("raw dashboard config writes are not implemented in the Java port"));
    }

    @Test
    void profileConfigRoutesReadNamedConfigInsteadOfGlobalRuntimeState() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        Path configPath = tempDir.resolve("profiles").resolve("work").resolve("config.yaml");
        Files.writeString(configPath, """
            model:
              provider: openrouter
              default: anthropic/claude-sonnet-4-5
              base_url: https://profile.example/v1
              api_key: profile-secret-key
            web:
              search_provider: searxng
              search_results: 7
            tts:
              provider: openai
              model: tts-profile
            stt:
              enabled: false
              provider: groq
            skills:
              default_toolsets:
                - file
            terminal:
              backend: powershell
            custom_section:
              keep: true
            _internal:
              hidden: yes
            """, StandardCharsets.UTF_8);
        runtimeConfigService.setModelSelection(
            "global-runtime",
            "global-runtime-model",
            "https://global-runtime.example/v1",
            "runtime-secret");

        String body = mockMvc.perform(get("/api/config").param("profile", "work"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model.provider").value("openrouter"))
            .andExpect(jsonPath("$.model.default").value("anthropic/claude-sonnet-4-5"))
            .andExpect(jsonPath("$.model.base_url").value("https://profile.example/v1"))
            .andExpect(jsonPath("$.web.search_provider").value("searxng"))
            .andExpect(jsonPath("$.web.search_results").value(7))
            .andExpect(jsonPath("$.tts.model").value("tts-profile"))
            .andExpect(jsonPath("$.stt.enabled").value(false))
            .andExpect(jsonPath("$.terminal.backend").value("powershell"))
            .andExpect(jsonPath("$.custom_section.keep").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(body)
            .doesNotContain("secret-model-key")
            .doesNotContain("runtime-secret")
            .doesNotContain("global-runtime-model")
            .doesNotContain("profile-secret-key")
            .doesNotContain("_internal");

        mockMvc.perform(get("/p/work/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model.provider").value("openrouter"));

        mockMvc.perform(get("/p/work/api/config/raw"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value(configPath.toString()))
            .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("provider: openrouter")));
    }

    @Test
    void profileConfigWritesDeepMergeIntoNamedProfileOnly() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        profileService.writeConfig("work", Map.of(
            "model", Map.of(
                "provider", "openrouter",
                "default", "old-model",
                "base_url", "https://profile.example/v1"),
            "custom_section", Map.of("keep", true)));

        mockMvc.perform(put("/p/work/api/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "config": {
                        "model": { "default": "new-model" },
                        "dashboard": { "theme": "midnight" }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        Map<String, Object> saved = profileService.readConfig("work");
        assertThat(saved).containsKey("custom_section");
        Map<?, ?> savedModel = (Map<?, ?>) saved.get("model");
        assertThat(savedModel.get("provider")).isEqualTo("openrouter");
        assertThat(savedModel.get("default")).isEqualTo("new-model");
        assertThat(savedModel.get("base_url")).isEqualTo("https://profile.example/v1");
        Map<?, ?> dashboard = (Map<?, ?>) saved.get("dashboard");
        assertThat(dashboard.get("theme")).isEqualTo("midnight");
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();
    }

    @Test
    void profileRawConfigWritesReplaceNamedProfileFileWithYamlMapping() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));

        mockMvc.perform(put("/p/work/api/config/raw")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yaml_text\":\"model:\\n  provider: groq\\n  default: llama-3\\n\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        assertThat(profileService.readRawConfig("work"))
            .contains("provider: groq")
            .contains("default: llama-3");

        mockMvc.perform(put("/p/work/api/config/raw")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yaml_text\":\"[]\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("YAML must be a mapping"));

        mockMvc.perform(put("/api/config/raw")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yaml_text\":\"model: {}\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void profileConfigRoutesFailClosedForUnknownAllAndMismatch() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));

        mockMvc.perform(get("/p/ghost/api/config"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(get("/api/config").param("profile", "all"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile=all is not supported for config"));

        mockMvc.perform(get("/p/work/api/config").param("profile", "default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));

        mockMvc.perform(get("/p/ghost/api/env"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));
    }

    @Test
    void providerAndOauthRoutesExposeSafeFallbackShapes() throws Exception {
        mockMvc.perform(post("/api/providers/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"OPENAI_API_KEY\",\"value\":\"bad\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.models").isArray());

        mockMvc.perform(get("/api/providers/custom-endpoints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.endpoints").isArray());

        mockMvc.perform(post("/api/providers/custom-endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("name required"));

        mockMvc.perform(post("/api/providers/custom-endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Local\",\"base_url\":\"not-a-url\",\"model\":\"local-model\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("base_url must include scheme and host"));

        mockMvc.perform(post("/api/providers/custom-endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Local\",\"base_url\":\"https://local.example/v1\",\"model\":\"local-model\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("custom endpoint persistence is not implemented in the Java port"));

        mockMvc.perform(post("/api/providers/custom-endpoints/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"base_url\":\"https://local.example/v1\",\"model\":\"local-model\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.models[0]").value("local-model"));

        mockMvc.perform(post("/api/providers/custom-endpoints/local/activate"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("custom endpoint not found"));

        mockMvc.perform(delete("/api/providers/custom-endpoints/local"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("custom endpoint not found"));

        mockMvc.perform(get("/api/providers/oauth"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers").isArray());

        String credentialPool = mockMvc.perform(get("/api/credentials/pool"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].provider").value("openai-compatible"))
            .andExpect(jsonPath("$.providers[0].entries[0].index").value(1))
            .andExpect(jsonPath("$.providers[0].entries[0].token_preview").value("secr...-key"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(credentialPool).doesNotContain("secret-model-key");

        mockMvc.perform(post("/api/credentials/pool")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("provider and api_key are required"));

        mockMvc.perform(post("/api/credentials/pool")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\",\"api_key\":\"secret\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("credential pool writes are not implemented in the Java port"));

        mockMvc.perform(delete("/api/credentials/pool/openai-compatible/1"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("credential pool deletion is not implemented in the Java port"));

        mockMvc.perform(post("/api/providers/oauth/nous/start"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(delete("/api/providers/oauth/sessions/session-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void dashboardThemeFontAndPluginProviderRoutesAvoidDashboard404s() throws Exception {
        mockMvc.perform(get("/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("default"))
            .andExpect(jsonPath("$.themes[0].name").value("default"))
            .andExpect(jsonPath("$.themes[0].label").value("Hermes Teal"));

        mockMvc.perform(put("/api/dashboard/theme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"midnight\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.theme").value("midnight"));

        mockMvc.perform(get("/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("midnight"));

        mockMvc.perform(get("/api/dashboard/font"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.font").value("theme"));

        mockMvc.perform(put("/api/dashboard/font")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"font\":\"work-sans\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.font").value("work-sans"));

        mockMvc.perform(put("/api/dashboard/font")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"font\":\"unknown-font\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.font").value("theme"));

        mockMvc.perform(put("/api/dashboard/plugin-providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memory_provider\":\"default\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("plugin provider persistence is not implemented in the Java port"));

        mockMvc.perform(get("/api/egress/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Egress proxy is not configured in the Java port."));
    }

    @Test
    void dashboardThemeAndFontAreScopedToNamedProfileConfig() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        profileService.writeConfig("work", Map.of(
            "dashboard", Map.of(
                "theme", "mono",
                "font", "jetbrains-mono")));

        mockMvc.perform(get("/p/work/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("mono"));
        mockMvc.perform(get("/p/work/api/dashboard/font"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.font").value("jetbrains-mono"));
        mockMvc.perform(get("/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("default"));

        mockMvc.perform(put("/p/work/api/dashboard/theme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"midnight\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.theme").value("midnight"));
        mockMvc.perform(put("/p/work/api/dashboard/font")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"font\":\"work-sans\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.font").value("work-sans"));

        Map<?, ?> dashboard = (Map<?, ?>) profileService.readConfig("work").get("dashboard");
        assertThat(dashboard.get("theme")).isEqualTo("midnight");
        assertThat(dashboard.get("font")).isEqualTo("work-sans");

        mockMvc.perform(get("/api/dashboard/font"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.font").value("theme"));
        mockMvc.perform(put("/p/work/api/dashboard/theme")
                .param("profile", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mono\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));
    }

    @Test
    void updateAndMaintenanceRoutesAvoidDashboard404s() throws Exception {
        mockMvc.perform(get("/api/hermes/update/check?force=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.install_method").value("managed-runtime"))
            .andExpect(jsonPath("$.update_available").value(false))
            .andExpect(jsonPath("$.can_apply").value(false))
            .andExpect(jsonPath("$.update_command").value("managed outside dashboard"))
            .andExpect(jsonPath("$.commits").isArray());

        mockMvc.perform(post("/api/hermes/update"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.name").value("hermes-update"))
            .andExpect(jsonPath("$.error").value("dashboard_update_managed_externally"));

        mockMvc.perform(get("/api/hermes/update/receipt"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("No update receipt found (no `hermes update` run recorded)."));

        mockMvc.perform(post("/api/gateway/restart"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("gateway restart is not implemented in the Java port"))
            .andExpect(jsonPath("$.error").value("gateway restart is not implemented in the Java port"));

        mockMvc.perform(post("/api/gateway/start"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("gateway start is not implemented in the Java port"));

        mockMvc.perform(post("/api/gateway/stop"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("gateway stop is not implemented in the Java port"));

        mockMvc.perform(post("/api/gateway/drain")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"drain\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("gateway drain is not implemented in the Java port"));

        mockMvc.perform(post("/api/gateway/drain")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"explode\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown drain action 'explode'; expected 'drain' or 'cancel'"));

        mockMvc.perform(post("/api/ops/doctor"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("doctor action is not implemented in the Java port"));

        mockMvc.perform(post("/api/ops/prompt-size"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("prompt-size action is not implemented in the Java port"));

        mockMvc.perform(post("/api/ops/dump"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("dump action is not implemented in the Java port"));

        mockMvc.perform(post("/api/ops/config-migrate"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("config migration action is not implemented in the Java port"));

        mockMvc.perform(post("/api/ops/security-audit"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/ops/backup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/ops/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("archive path is required"));

        mockMvc.perform(post("/api/ops/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"archive\":\"definitely-missing-java-agent-backup.zip\",\"force\":true}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value(
                "Archive not found: definitely-missing-java-agent-backup.zip"));

        java.nio.file.Path importArchive = java.nio.file.Files.createTempFile("java-agent-import", ".zip");
        try {
            mockMvc.perform(post("/api/ops/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"archive\":\"" + importArchive.toString().replace("\\", "\\\\") + "\",\"force\":true}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.detail").value("import action is not implemented in the Java port"));
        } finally {
            java.nio.file.Files.deleteIfExists(importArchive);
        }

        mockMvc.perform(post("/api/ops/import-upload")
                .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("import upload is not implemented in the Java port"));

        mockMvc.perform(get("/api/ops/backup/download?archive=definitely-missing-java-agent-backup.zip"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Backup not found"));

        java.nio.file.Path downloadArchive = java.nio.file.Files.createTempFile("java-agent-backup", ".zip");
        try {
            mockMvc.perform(get("/api/ops/backup/download")
                    .param("archive", downloadArchive.toString()))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.detail").value("backup download is not implemented in the Java port"));
        } finally {
            java.nio.file.Files.deleteIfExists(downloadArchive);
        }

        mockMvc.perform(post("/api/ops/debug-share")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redact\":true}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("debug share is not implemented in the Java port"));

        mockMvc.perform(get("/api/ops/hooks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hooks").isArray())
            .andExpect(jsonPath("$.valid_events").isArray());

        mockMvc.perform(post("/api/ops/hooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("event and command are required"));

        mockMvc.perform(post("/api/ops/hooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"pre_tool_call\",\"command\":\"echo ok\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("shell hook creation is not implemented in the Java port"));

        mockMvc.perform(delete("/api/ops/hooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("event and command are required"));

        mockMvc.perform(delete("/api/ops/hooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"pre_tool_call\",\"command\":\"echo ok\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("shell hook deletion is not implemented in the Java port"));

        mockMvc.perform(get("/api/ops/checkpoints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions").isArray())
            .andExpect(jsonPath("$.total_bytes").isNumber());

        mockMvc.perform(post("/api/ops/checkpoints/prune"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("checkpoint pruning is not implemented in the Java port"));
    }
}
