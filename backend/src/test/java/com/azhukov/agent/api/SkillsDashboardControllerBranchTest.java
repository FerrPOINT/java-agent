package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillsHubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch coverage for SkillsDashboardController: validation rejects, hub
 * endpoints, error mapping — standalone harness (no shared fixtures).
 */
class SkillsDashboardControllerBranchTest {

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private MiniSkillManager skillManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        skillManager = new MiniSkillManager();
        SkillsHubService hubService = new SkillsHubService(skillManager, properties, mock(MemoryThreatScanner.class));
        mockMvc = MockMvcBuilders.standaloneSetup(
            new SkillsDashboardController(skillManager, properties, hubService)).build();
    }

    private static SkillManager.SkillInfo info(String name) {
        return new SkillManager.SkillInfo(
            name, "", "A skill", "general", Instant.EPOCH, 0, 0,
            Instant.EPOCH, false, "AGENT_CREATED", List.of(), List.of(), false, null);
    }

    @Test
    void toggleRejectsMissingEnabled() throws Exception {
        mockMvc.perform(put("/api/skills/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"alpha\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("enabled is required"));
    }

    @Test
    void toggleUnknownSkillStillTogglesDisabledList() throws Exception {
        mockMvc.perform(put("/api/skills/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\",\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void listSkillsMapsEnumerationFailureTo500() throws Exception {
        skillManager.failList = true;
        mockMvc.perform(get("/api/skills"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("Failed to enumerate skills"));
    }

    @Test
    void createRejectsMissingContent() throws Exception {
        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-skill\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("content is required"));
    }

    @Test
    void createRejectsInvalidSkillName() throws Exception {
        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad name!\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsExistingSkill() throws Exception {
        skillManager.skills.put("existing", info("existing"));

        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"existing\",\"content\":\"body\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("already exists")));
    }

    @Test
    void updateUnknownSkillReturns404() throws Exception {
        mockMvc.perform(put("/api/skills/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\",\"content\":\"body\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void contentGetUnknownSkillReturns404() throws Exception {
        mockMvc.perform(get("/api/skills/content?name=ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    void contentGetRejectsBlankName() throws Exception {
        mockMvc.perform(get("/api/skills/content"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void hubSourcesListed() throws Exception {
        mockMvc.perform(get("/api/skills/hub/sources?profile=default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sources[0].id").value("github"))
            .andExpect(jsonPath("$.index_available").value(false));
    }

    @Test
    void hubSearchReturnsOkWithoutRemote() throws Exception {
        mockMvc.perform(get("/api/skills/hub/search?q=test"))
            .andExpect(status().isOk());
    }

    @Test
    void hubScanRejectsMissingIdentifier() throws Exception {
        mockMvc.perform(get("/api/skills/hub/scan"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("identifier is required"));
    }

    @Test
    void hubInstallRejectsMissingIdentifier() throws Exception {
        mockMvc.perform(post("/api/skills/hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("identifier is required"));
    }

    @Test
    void hubInstallWithoutInstallerReturns501() throws Exception {
        mockMvc.perform(post("/api/skills/hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"owner/repo/skill\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void hubUninstallWithoutInstallerReturns501() throws Exception {
        mockMvc.perform(post("/api/skills/hub/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\"}"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void hubUpdateWithoutInstallerReturns501() throws Exception {
        mockMvc.perform(post("/api/skills/hub/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\"}"))
            .andExpect(status().isNotImplemented());
    }

    /** Minimal in-memory SkillManager sufficient for the dashboard branches. */
    private static final class MiniSkillManager implements SkillManager {
        final Map<String, SkillInfo> skills = new LinkedHashMap<>();
        boolean failList;

        @Override
        public List<String> listSkillNames() {
            if (failList) {
                throw new RuntimeException("boom");
            }
            return new ArrayList<>(skills.keySet());
        }

        @Override
        public List<SkillInfo> listSkills() {
            if (failList) {
                throw new RuntimeException("boom");
            }
            return new ArrayList<>(skills.values());
        }

        @Override
        public String getSkill(String name) {
            return skills.containsKey(name) ? "content" : null;
        }

        @Override
        public void saveSkill(String name, String content) {
            skills.put(name, info(name));
        }

        @Override
        public boolean deleteSkill(String name) {
            return skills.remove(name) != null;
        }

        @Override
        public SkillLookupResult getSkillInfoMultiStrategy(String name) {
            return new SkillLookupResult(skills.get(name), List.of(), null);
        }
    }
}
