package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage + regression for DashboardSystemController branch paths:
 * checkpoints listing/prune, system stats, dashboard theme set/get.
 */
class DashboardSystemControllerBranchTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private AgentProperties properties;

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
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService();
        ProfileService profileService = new ProfileService(properties, runtimeConfigService);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new DashboardSystemController(properties, runtimeConfigService, profileService)).build();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("hermes.home");
    }

    @Test
    void checkpointsListReturnsSessionsShape() throws Exception {
        mockMvc.perform(get("/api/ops/checkpoints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions").exists())
            .andExpect(jsonPath("$.total_bytes").isNumber());
    }

    @Test
    void checkpointsListWithSessionDirsCountsBytes() throws Exception {
        Path sessionDir = tempDir.resolve("checkpoints").resolve("abc123");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("checkpoint.json"), "{\"turn\":1}");
        mockMvc.perform(get("/api/ops/checkpoints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions.length()").value(1))
            .andExpect(jsonPath("$.total_bytes").value((int) Files.size(sessionDir.resolve("checkpoint.json"))));
    }

    @Test
    void checkpointsPruneIsExplicitlyNotImplemented() throws Exception {
        mockMvc.perform(post("/api/ops/checkpoints/prune"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void systemStatsReturnsMemoryShape() throws Exception {
        mockMvc.perform(get("/api/system/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memory.total").isNumber())
            .andExpect(jsonPath("$.memory.used").isNumber())
            .andExpect(jsonPath("$.memory.percent").isNumber());
    }

    @Test
    void dashboardThemesListedAndActiveIsDefault() throws Exception {
        mockMvc.perform(get("/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.themes").isArray())
            .andExpect(jsonPath("$.active").value("default"));
    }

    @Test
    void dashboardThemeSetPersistsPreference() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dashboard/theme")
                .contentType("application/json")
                .content("{\"name\":\"dark\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.theme").value("dark"));
    }
}
