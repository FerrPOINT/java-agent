package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ToolsetsControllerTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;
    private ToolRegistry toolRegistry;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        properties = mock(AgentProperties.class);

        AgentProperties.SkillsProperties skillsProps = new AgentProperties.SkillsProperties();
        skillsProps.setDefaultToolsets(List.of("hermes-cli"));
        when(properties.getSkills()).thenReturn(skillsProps);

        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        apiProps.setChatCompletionToolsets(List.of("hermes-api-server"));
        when(properties.getApi()).thenReturn(apiProps);

        // Set up two toolsets with tools
        ToolDefinition webSearch = new ToolDefinition("web_search", "Search the web", Map.of());
        ToolDefinition termExec = new ToolDefinition("terminal", "Execute terminal command", Map.of());

        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal", "hermes-cli", "hermes-api-server"));
        when(toolRegistry.getDefinitions(Set.of("terminal"))).thenReturn(List.of(termExec));
        when(toolRegistry.getDefinitions(Set.of("hermes-cli"))).thenReturn(List.of(webSearch, termExec));
        when(toolRegistry.getDefinitions(Set.of("hermes-api-server"))).thenReturn(List.of(webSearch, termExec));

        mockMvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, properties)).build();
    }

    @Test
    void listToolsetsReturnsAllToolsets() throws Exception {
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.platform").value("api_server"))
            .andExpect(jsonPath("$.data.length()").value(26));
    }

    @Test
    void profilePrefixedToolsetsRouteUsesProfileConfigInsteadOfGlobalApiDefaults() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        ProfileService profileService = profileService(realProperties);
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        profileService.writeConfig("work", Map.of(
            "platform_toolsets", Map.of("api_server", List.of("terminal"))
        ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ToolsetsController(toolRegistry, realProperties, profileService)).build();

        mvc.perform(get("/p/work/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.platform").value("api_server"))
            .andExpect(jsonPath("$.data[?(@.name=='terminal')].enabled").value(true))
            .andExpect(jsonPath("$.data[?(@.name=='web')].enabled").value(false));

        assertThat(realProperties.getApi().getChatCompletionToolsets()).containsExactly("hermes-api-server");
    }

    @Test
    void toolsetContainsTools() throws Exception {
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("web"))
            .andExpect(jsonPath("$.data[0].label").value("🔍 Web Search & Scraping"))
            .andExpect(jsonPath("$.data[0].description").value("web_search, web_extract"))
            .andExpect(jsonPath("$.data[?(@.name=='web')].tools[0]").value("web_extract"))
            .andExpect(jsonPath("$.data[?(@.name=='web')].tools[1]").value("web_search"))
            .andExpect(jsonPath("$.data[?(@.name=='terminal')].tools[0]").value("process"))
            .andExpect(jsonPath("$.data[?(@.name=='terminal')].tools[1]").value("terminal"));
    }

    @Test
    void dashboardToolsetsRouteReturnsHermesListShape() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].name").value("web"))
            .andExpect(jsonPath("$[0].label").value("Web Search & Scraping"))
            .andExpect(jsonPath("$[0].platform").value("cli"))
            .andExpect(jsonPath("$[0].platform_label").value("CLI"))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].available").value(true))
            .andExpect(jsonPath("$[0].tools[0]").value("web_extract"))
            .andExpect(jsonPath("$[0].tools[1]").value("web_search"))
            .andExpect(jsonPath("$[?(@.name=='discord')].platform").value("discord"))
            .andExpect(jsonPath("$[?(@.name=='discord')].platform_label").value("Discord"))
            .andExpect(jsonPath("$[?(@.name=='discord')].enabled").value(false));
    }

    @Test
    void profilePrefixedDashboardToolsetRoutesReadAndWriteNamedProfileConfig() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        realProperties.getWeb().setSearchProvider("ddg");
        ProfileService profileService = profileService(realProperties);
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        profileService.writeConfig("work", Map.of(
            "platform_toolsets", Map.of("cli", List.of("terminal")),
            "web", Map.of("backend", "searxng", "searxng_url", "http://localhost:8088")
        ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ToolsetsController(toolRegistry, realProperties, profileService)).build();

        mvc.perform(get("/p/work/api/tools/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[?(@.name=='terminal')].enabled").value(true))
            .andExpect(jsonPath("$[?(@.name=='web')].enabled").value(false));

        mvc.perform(get("/p/work/api/tools/toolsets/web/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("web"))
            .andExpect(jsonPath("$.active_provider").value("searxng"))
            .andExpect(jsonPath("$.active_search_backend").value("searxng"));

        mvc.perform(put("/p/work/api/tools/toolsets/web")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("web"));

        assertThat(realProperties.getSkills().getDefaultToolsets()).containsExactly("hermes-cli");
        @SuppressWarnings("unchecked")
        Map<String, Object> platformToolsets = (Map<String, Object>) profileService.readConfig("work").get("platform_toolsets");
        assertThat((List<String>) platformToolsets.get("cli")).contains("terminal", "web");
    }

    @Test
    void profilePrefixedToolsetWritesFailClosedForUnknownAndMismatchedProfiles() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        ProfileService profileService = profileService(realProperties);
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ToolsetsController(toolRegistry, realProperties, profileService)).build();

        mvc.perform(get("/p/ghost/api/tools/toolsets"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mvc.perform(put("/p/work/api/tools/toolsets/web?profile=other")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"profile\":\"work\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));

        assertThat(realProperties.getSkills().getDefaultToolsets()).containsExactly("hermes-cli");
    }

    @Test
    void dashboardToggleUsesHermesPutShape() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/web")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("web"))
            .andExpect(jsonPath("$.platform").value("cli"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.post_setup_started").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void dashboardToggleDisablesToolsetInheritedFromHermesCliComposite() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        ToolsetsController controller = new ToolsetsController(toolRegistry, realProperties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(put("/api/tools/toolsets/web")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.platform").value("cli"))
            .andExpect(jsonPath("$.enabled").value(false));

        assertThat(realProperties.getSkills().getDefaultToolsets())
            .doesNotContain("web")
            .contains("terminal", "file", "skills");

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) controller.listDashboardToolsetsPayload();

        assertThat(data)
            .filteredOn(entry -> "web".equals(entry.get("name")))
            .singleElement()
            .satisfies(entry -> assertThat(entry).containsEntry("enabled", false));
        assertThat(data)
            .filteredOn(entry -> "terminal".equals(entry.get("name")))
            .singleElement()
            .satisfies(entry -> assertThat(entry).containsEntry("enabled", true));
    }

    @Test
    void dashboardToggleRejectsUnknownToolsetLikeHermes() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/not_a_toolset")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown toolset: not_a_toolset"));
    }

    @Test
    void dashboardToolsetConfigReturnsProviderMatrixWithoutSecretValues() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        realProperties.getWeb().setSearxngUrl("http://localhost:8088");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, realProperties)).build();

        mvc.perform(get("/api/tools/toolsets/web/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("web"))
            .andExpect(jsonPath("$.has_category").value(true))
            .andExpect(jsonPath("$.active_provider").value("searxng"))
            .andExpect(jsonPath("$.active_search_backend").value("searxng"))
            .andExpect(jsonPath("$.providers[?(@.name=='searxng')].env_vars[0].key").value("AGENT_WEB_SEARXNG_URL"))
            .andExpect(jsonPath("$.providers[?(@.name=='searxng')].env_vars[0].is_set").value(true))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http://localhost:8088"))));
    }

    @Test
    void dashboardToolsetConfigRejectsUnknownToolsetLikeHermes() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/not_a_toolset/config"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown toolset: not_a_toolset"));
    }

    @Test
    void dashboardToolsetModelsReturnsHermesEmptyCatalogShape() throws Exception {
        mockMvc.perform(get("/api/tools/toolsets/image_gen/models?provider=openai"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("image_gen"))
            .andExpect(jsonPath("$.has_models").value(false))
            .andExpect(jsonPath("$.provider").value("openai"))
            .andExpect(jsonPath("$.plugin").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.models").isArray())
            .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.default").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void dashboardToolsetProviderCanSelectJavaWebSearchBackend() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ToolsetsController(toolRegistry, realProperties)).build();

        mvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"searxng\",\"capability\":\"search\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("web"))
            .andExpect(jsonPath("$.provider").value("searxng"))
            .andExpect(jsonPath("$.capability").value("search"));

        assertThat(realProperties.getWeb().getSearchProvider()).isEqualTo("searxng");
        mvc.perform(get("/api/tools/toolsets/web/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_provider").value("searxng"));
    }

    @Test
    void dashboardToolsetProviderSelectionWritesNamedProfileConfigOnly() throws Exception {
        AgentProperties realProperties = new AgentProperties();
        realProperties.getWeb().setSearchProvider("ddg");
        ProfileService profileService = profileService(realProperties);
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ToolsetsController(toolRegistry, realProperties, profileService)).build();

        mvc.perform(put("/p/work/api/tools/toolsets/web/provider")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"searxng\",\"capability\":\"search\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("web"))
            .andExpect(jsonPath("$.provider").value("searxng"))
            .andExpect(jsonPath("$.capability").value("search"));

        assertThat(realProperties.getWeb().getSearchProvider()).isEqualTo("ddg");
        @SuppressWarnings("unchecked")
        Map<String, Object> web = (Map<String, Object>) profileService.readConfig("work").get("web");
        assertThat(web).containsEntry("search_backend", "searxng");

        mvc.perform(get("/p/work/api/tools/toolsets/web/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_search_backend").value("searxng"))
            .andExpect(jsonPath("$.active_extract_backend").value("jsoup"));
    }

    @Test
    void dashboardToolsetWritesWithoutJavaConfigLayerFailExplicitly() throws Exception {
        mockMvc.perform(put("/api/tools/toolsets/image_gen/model")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"model\":\"gpt-image-1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Toolset has no model catalog: image_gen"));

        mockMvc.perform(put("/api/tools/toolsets/web/provider")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"ddg\",\"capability\":\"extract\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("web extract backend selection is not implemented in the Java port"));

        mockMvc.perform(put("/api/tools/toolsets/web/env")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"env\":{\"AGENT_WEB_SEARXNG_URL\":\"http://localhost:8888\"}}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/tools/toolsets/web/post-setup")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("key is required"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/tools/toolsets/web/post-setup")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"key\":\"browser\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void terminalAndComputerUseDashboardRoutesExposeStableJavaPortShapes() throws Exception {
        mockMvc.perform(get("/api/tools/terminal/backends"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("local"))
            .andExpect(jsonPath("$.backends[0].name").value("local"))
            .andExpect(jsonPath("$.backends[0].status").value("ready"));

        mockMvc.perform(put("/api/tools/terminal/backend")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"backend\":\"local\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.backend").value("local"));

        mockMvc.perform(get("/api/tools/computer-use/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.platform").exists())
            .andExpect(jsonPath("$.platform_supported").value(false))
            .andExpect(jsonPath("$.installed").value(false))
            .andExpect(jsonPath("$.ready").value(false))
            .andExpect(jsonPath("$.checks[0].status").value("unavailable"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/tools/computer-use/permissions/grant"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Computer Use permission grants are not implemented in the Java port"));
    }

    @Test
    void configurableToolsetsExposeHermesStaticToolNamesEvenWhenRegistryDoesNotHaveImplementations() throws Exception {
        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.name=='x_search')].tools[0]").value("x_search"))
            .andExpect(jsonPath("$.data[?(@.name=='video')].tools[0]").value("video_analyze"))
            .andExpect(jsonPath("$.data[?(@.name=='video_gen')].tools[0]").value("video_generate"))
            .andExpect(jsonPath("$.data[?(@.name=='video_gen')].tools[1]").value("xai_video_edit"))
            .andExpect(jsonPath("$.data[?(@.name=='video_gen')].tools[2]").value("xai_video_extend"))
            .andExpect(jsonPath("$.data[?(@.name=='spotify')].tools.length()").value(7))
            .andExpect(jsonPath("$.data[?(@.name=='context_engine')].tools.length()").value(0))
            .andExpect(jsonPath("$.data[?(@.name=='stt')].tools.length()").value(0));
    }

    @Test
    void toolsetEnabledStateReflectsDefaultToolsets() {
        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsetsPayload();

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) result.get("data");

        assertThat(data)
            .filteredOn(entry -> "web".equals(entry.get("name")))
            .singleElement()
            .satisfies(entry -> assertThat(entry).containsEntry("enabled", true));
        assertThat(data)
            .filteredOn(entry -> "terminal".equals(entry.get("name")))
            .singleElement()
            .satisfies(entry -> assertThat(entry).containsEntry("enabled", true));
        assertThat(data).noneMatch(entry -> "hermes-cli".equals(entry.get("name")));
        assertThat(data).noneMatch(entry -> "hermes-api-server".equals(entry.get("name")));
    }

    @Test
    void dynamicRegistryToolsetsAreAppendedAfterHermesConfigurableList() {
        ToolDefinition mcpPing = new ToolDefinition("mcp__my_server__ping", "Ping", Map.of());
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "terminal", "hermes-cli", "hermes-api-server", "mcp-my-server"));
        when(toolRegistry.getDefinitions(Set.of("mcp-my-server"))).thenReturn(List.of(mcpPing));

        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsetsPayload();

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) result.get("data");

        assertThat(data.get(25)).containsEntry("name", "computer_use");
        assertThat(data.get(26)).containsEntry("name", "mcp-my-server");
        assertThat(data.get(26)).containsEntry("label", "mcp-my-server");
        assertThat(data.get(26).get("tools")).isEqualTo(List.of("mcp__my_server__ping"));
    }

    @Test
    void mcpRegistryToolsetUsesRawServerAliasForEnabledState() {
        AgentProperties.ApiProperties apiProps = new AgentProperties.ApiProperties();
        apiProps.setChatCompletionToolsets(List.of("my-server"));
        when(properties.getApi()).thenReturn(apiProps);
        ToolDefinition mcpPing = new ToolDefinition("mcp__my_server__ping", "Ping", Map.of());
        when(toolRegistry.getToolsets()).thenReturn(Set.of("mcp-my-server"));
        when(toolRegistry.getDefinitions(Set.of("mcp-my-server"))).thenReturn(List.of(mcpPing));

        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsetsPayload();

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) result.get("data");

        assertThat(data)
            .filteredOn(entry -> "mcp-my-server".equals(entry.get("name")))
            .singleElement()
            .satisfies(entry -> {
                assertThat(entry).containsEntry("enabled", true);
                assertThat(entry.get("tools")).isEqualTo(List.of("mcp__my_server__ping"));
            });
    }

    @Test
    void configuredStateFollowsHermesKeyRules() {
        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsetsPayload();

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) result.get("data");

        assertThat(data)
            .filteredOn(entry -> "browser".equals(entry.get("name")))
            .extracting(entry -> entry.get("configured"))
            .containsExactly(true);
        assertThat(data)
            .filteredOn(entry -> "vision".equals(entry.get("name")))
            .extracting(entry -> entry.get("configured"))
            .containsExactly(false);
    }

    @Test
    void directCallReturnsMap() {
        var controller = new ToolsetsController(toolRegistry, properties);
        var result = controller.listToolsetsPayload();
        assertThat(result).containsEntry("object", "list");
        assertThat(result).containsEntry("platform", "api_server");
        @SuppressWarnings("unchecked")
        var data = (List<?>) result.get("data");
        assertThat(data).hasSize(26);
    }

    @Test
    void failureReturnsStableHermesServerError() throws Exception {
        when(toolRegistry.getToolsets()).thenThrow(new RuntimeException("secret backend detail"));

        mockMvc.perform(get("/v1/toolsets"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.message").value("Failed to enumerate toolsets"))
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.error.code").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret backend detail"))));
    }

    private ProfileService profileService(AgentProperties properties) {
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        return new ProfileService(properties, new RuntimeConfigService());
    }
}
