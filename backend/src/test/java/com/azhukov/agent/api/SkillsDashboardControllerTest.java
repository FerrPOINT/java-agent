package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillsHubService;
import com.azhukov.agent.core.skill.TrustLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;

class SkillsDashboardControllerTest {

    private static final String SKILL_MD = "---\n"
        + "name: %s\n"
        + "description: a test skill\n"
        + "---\n\n"
        + "# %s\n\n"
        + "Do the thing.\n";

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private InMemorySkillManager skillManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory("C:\\work");
        skillManager = new InMemorySkillManager();
        SkillsHubService hubService = new SkillsHubService(skillManager, properties, mock(MemoryThreatScanner.class));
        mockMvc = MockMvcBuilders.standaloneSetup(
            new SkillsDashboardController(skillManager, properties, hubService)).build();
    }

    @Test
    void listSkillsReturnsDesktopShapeWithEnabledUsageAndProvenance() throws Exception {
        skillManager.put(info("zeta", "Z skill", "tools", 1, 2, "AGENT_CREATED", false));
        skillManager.put(info("alpha", "A skill", "core", 4, 5, "BUILTIN", false));
        skillManager.put(info("hidden", "Hidden", "core", 0, 0, "AGENT_CREATED", true));
        properties.getSkills().getDisabled().add("zeta");

        mockMvc.perform(get("/api/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("alpha"))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].usage").value(9))
            .andExpect(jsonPath("$[0].provenance").value("bundled"))
            .andExpect(jsonPath("$[1].name").value("hidden"))
            .andExpect(jsonPath("$[1].enabled").value(false))
            .andExpect(jsonPath("$[2].name").value("zeta"))
            .andExpect(jsonPath("$[2].enabled").value(false))
            .andExpect(jsonPath("$[2].usage").value(3))
            .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void toggleSkillMutatesRuntimeDisabledList() throws Exception {
        mockMvc.perform(put("/api/skills/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"alpha\",\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("alpha"))
            .andExpect(jsonPath("$.enabled").value(false));

        assertThat(properties.getSkills().getDisabled()).containsExactly("alpha");

        mockMvc.perform(put("/api/skills/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"alpha\",\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));

        assertThat(properties.getSkills().getDisabled()).doesNotContain("alpha");
    }

    @Test
    void contentAndEditorRoutesUseValidatedSkillManagerPath() throws Exception {
        String content = "---\nname: dashboard-skill\ndescription: Test\n---\nBody";
        skillManager.saveSkill("dashboard-skill", content);

        mockMvc.perform(get("/api/skills/content?name=dashboard-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("dashboard-skill"))
            .andExpect(jsonPath("$.content").value(content))
            .andExpect(jsonPath("$.path").value("C:\\work\\skills\\dashboard-skill\\SKILL.md"));

        mockMvc.perform(put("/api/skills/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dashboard-skill\",\"content\":\"---\\nname: dashboard-skill\\ndescription: New\\n---\\nNew body\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(skillManager.getSkill("dashboard-skill")).contains("New body");
    }

    @Test
    void updateUsesMultiStrategyLookupForVisibleFilesystemSkill() throws Exception {
        skillManager.putLookupOnly(info("fs-skill", "FS", "general", 0, 0, "AGENT_CREATED", false));

        mockMvc.perform(put("/api/skills/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"fs-skill\",\"content\":\"---\\nname: fs-skill\\ndescription: New\\n---\\nNew body\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Skill 'fs-skill' updated (full rewrite)."));

        assertThat(skillManager.getSkill("fs-skill")).contains("New body");
    }

    @Test
    void createRejectsExistingSkillFoundByMultiStrategyLookup() throws Exception {
        skillManager.putLookupOnly(info("fs-skill", "FS", "general", 0, 0, "AGENT_CREATED", false));

        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"fs-skill\",\"content\":\"---\\nname: fs-skill\\ndescription: New\\n---\\nNew body\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Skill 'fs-skill' already exists."));
    }

    @Test
    void createRejectsInvalidAndUnknownUpdateReturns404() throws Exception {
        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"../escape\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/skills/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"missing\",\"content\":\"---\\nname: missing\\ndescription: New\\n---\\nNew body\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void hubReadOnlyRoutesExposeStableDesktopShapesAndWritesFailExplicitly() throws Exception {
        mockMvc.perform(get("/api/skills/hub/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sources[0].id").value("github"))
            .andExpect(jsonPath("$.index_available").value(false))
            .andExpect(jsonPath("$.featured").isArray())
            .andExpect(jsonPath("$.installed").isMap());

        mockMvc.perform(get("/api/skills/hub/search?q="))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results").isArray())
            .andExpect(jsonPath("$.source_counts").isMap())
            .andExpect(jsonPath("$.timed_out").isArray())
            .andExpect(jsonPath("$.installed").isMap());

        mockMvc.perform(get("/api/skills/hub/preview?identifier="))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("identifier is required"));

        mockMvc.perform(get("/api/skills/hub/scan?identifier="))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("identifier is required"));

        mockMvc.perform(post("/api/skills/hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"demo\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/skills/hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("identifier is required"));

        mockMvc.perform(post("/api/skills/hub/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"demo\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/skills/hub/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("name is required"));
    }

    @Test
    void hubPreviewAndScanReturnHermesLikeReadOnlyPayloads() throws Exception {
        SkillsHubService hubService = mock(SkillsHubService.class);
        when(hubService.preview("demo")).thenReturn(new SkillsHubService.HubPreview(
            "demo", "Demo", "github", "demo", TrustLevel.COMMUNITY, "https://github.com/user/repo",
            List.of("java"), "---\nname: demo\ndescription: Demo\n---\nBody", List.of("SKILL.md", "references/ref.md")));
        when(hubService.scan("demo")).thenReturn(new SkillsHubService.HubScan(
            "demo", "demo", "github", TrustLevel.COMMUNITY, "caution", "Found one",
            "block", "blocked by install policy",
            List.of(Map.of("severity", "medium", "category", "injection", "file", "SKILL.md", "line", 3,
                "description", "prompt injection")),
            Map.of("critical", 0, "high", 0, "medium", 1, "low", 0)));
        MockMvc hubMvc = MockMvcBuilders.standaloneSetup(
            new SkillsDashboardController(skillManager, properties, hubService)).build();

        hubMvc.perform(get("/api/skills/hub/preview?identifier=demo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("demo"))
            .andExpect(jsonPath("$.trust_level").value("community"))
            .andExpect(jsonPath("$.skill_md").value("---\nname: demo\ndescription: Demo\n---\nBody"))
            .andExpect(jsonPath("$.files[1]").value("references/ref.md"));

        hubMvc.perform(get("/api/skills/hub/scan?identifier=demo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("caution"))
            .andExpect(jsonPath("$.policy").value("block"))
            .andExpect(jsonPath("$.severity_counts.medium").value(1))
            .andExpect(jsonPath("$.findings[0].category").value("injection"));
    }

    @Test
    void hubPreviewAndScanReturn404WhenSingleRepoHubCannotResolveSkill() throws Exception {
        SkillsHubService hubService = mock(SkillsHubService.class);
        when(hubService.preview("missing")).thenReturn(null);
        when(hubService.scan("missing")).thenReturn(null);
        MockMvc hubMvc = MockMvcBuilders.standaloneSetup(
            new SkillsDashboardController(skillManager, properties, hubService)).build();

        hubMvc.perform(get("/api/skills/hub/preview?identifier=missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Skill not found: missing"));

        hubMvc.perform(get("/api/skills/hub/scan?identifier=missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Skill not found: missing"));
    }

    @Test
    void profileScopedListAndContentReadNamedProfileOnly() throws Exception {
        ProfileService profileService = profileService();
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "worker_alpha", null, false, false, true, null, null, null, null));
        writeProfileSkill(profileService, "worker_alpha", "worker-skill");
        skillManager.saveSkill("dashboard-skill", SKILL_MD.formatted("dashboard-skill", "dashboard-skill"));
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillsDashboardController(
            skillManager, properties, hubService(), profileService)).build();

        mockMvc.perform(get("/api/skills").param("profile", "worker_alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("worker-skill"))
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/skills/content")
                .param("name", "worker-skill")
                .param("profile", "worker_alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("worker-skill"))
            .andExpect(jsonPath("$.content").value(SKILL_MD.formatted("worker-skill", "worker-skill")));

        mockMvc.perform(get("/api/skills/content").param("name", "worker-skill"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/p/worker_alpha/api/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("worker-skill"));
    }

    @Test
    void profileScopedToggleWritesTargetProfileConfigOnly() throws Exception {
        ProfileService profileService = profileService();
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "worker_alpha", null, false, false, true, null, null, null, null));
        writeProfileSkill(profileService, "worker_alpha", "worker-skill");
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillsDashboardController(
            skillManager, properties, hubService(), profileService)).build();

        mockMvc.perform(put("/api/skills/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"worker-skill\",\"enabled\":false,\"profile\":\"worker_alpha\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.enabled").value(false));

        assertThat(properties.getSkills().getDisabled()).doesNotContain("worker-skill");
        assertThat(Files.readString(profileService.profilePath("worker_alpha").resolve("config.yaml")))
            .contains("worker-skill");

        mockMvc.perform(get("/api/skills").param("profile", "worker_alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    void profileScopedEditorWritesNamedProfileAndFailsClosed() throws Exception {
        ProfileService profileService = profileService();
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "worker_alpha", null, false, false, true, null, null, null, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillsDashboardController(
            skillManager, properties, hubService(), profileService)).build();

        mockMvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-worker\",\"profile\":\"worker_alpha\",\"content\":\""
                    + escaped(SKILL_MD.formatted("new-worker", "new-worker")) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        Path skillFile = profileService.profilePath("worker_alpha")
            .resolve("skills").resolve("new-worker").resolve("SKILL.md");
        assertThat(Files.readString(skillFile)).contains("Do the thing.");

        String updated = SKILL_MD.formatted("new-worker", "new-worker")
            .replace("Do the thing.", "Do the scoped thing.");
        mockMvc.perform(put("/p/worker_alpha/api/skills/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-worker\",\"content\":\"" + escaped(updated) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(Files.readString(skillFile)).contains("Do the scoped thing.");
        mockMvc.perform(get("/api/skills/content").param("name", "new-worker"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/p/worker_alpha/api/skills").param("profile", "default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));

        mockMvc.perform(post("/api/skills/hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"official/demo\",\"profile\":\"ghost\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));
    }

    @Test
    void listFailureDoesNotLeakExceptionDetails() throws Exception {
        SkillManager throwing = mock(SkillManager.class);
        when(throwing.listSkills()).thenThrow(new RuntimeException("secret internal path"));
        MockMvc failingMvc = MockMvcBuilders.standaloneSetup(
            new SkillsDashboardController(throwing, properties,
                new SkillsHubService(throwing, properties, mock(MemoryThreatScanner.class)))).build();

        failingMvc.perform(get("/api/skills"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("Failed to enumerate skills"));
    }

    private static SkillManager.SkillInfo info(
        String name,
        String description,
        String category,
        int views,
        int manages,
        String trust,
        boolean disabled
    ) {
        return new SkillManager.SkillInfo(
            name, "", description, category, Instant.EPOCH, views, manages,
            Instant.EPOCH, false, trust, List.of(), List.of(), disabled, null);
    }

    private SkillsHubService hubService() {
        return new SkillsHubService(skillManager, properties, mock(MemoryThreatScanner.class));
    }

    private ProfileService profileService() {
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        return new ProfileService(properties, new RuntimeConfigService());
    }

    private static void writeProfileSkill(ProfileService profileService, String profile, String name) throws Exception {
        Path skillDir = profileService.profilePath(profile).resolve("skills").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MD.formatted(name, name), StandardCharsets.UTF_8);
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static final class InMemorySkillManager implements SkillManager {
        private final Map<String, SkillInfo> skills = new LinkedHashMap<>();
        private final Map<String, SkillInfo> lookupOnly = new LinkedHashMap<>();

        void put(SkillInfo info) {
            skills.put(info.name(), info);
        }

        void putLookupOnly(SkillInfo info) {
            lookupOnly.put(info.name(), info);
        }

        @Override
        public List<String> listSkillNames() {
            return new ArrayList<>(skills.keySet());
        }

        @Override
        public String getSkill(String name) {
            SkillInfo info = skills.get(name);
            return info != null ? info.content() : null;
        }

        @Override
        public void saveSkill(String name, String content) {
            saveSkill(name, content, com.azhukov.agent.core.skill.WriteOrigin.USER);
        }

        @Override
        public void saveSkill(String name, String content, com.azhukov.agent.core.skill.WriteOrigin origin) {
            if (name == null || !name.matches("^[a-z0-9][a-z0-9._-]*$")) {
                throw new IllegalArgumentException("Invalid skill name");
            }
            if (content == null || !content.startsWith("---")) {
                throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter");
            }
            skills.put(name, new SkillInfo(name, content, "Test", "general", Instant.EPOCH,
                0, 0, Instant.EPOCH, false, "AGENT_CREATED", List.of(), List.of(), false, null));
        }

        @Override
        public boolean deleteSkill(String name) {
            return skills.remove(name) != null;
        }

        @Override
        public List<SkillInfo> listSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public SkillLookupResult getSkillInfoMultiStrategy(String name) {
            SkillInfo info = skills.get(name);
            if (info == null) {
                info = lookupOnly.get(name);
            }
            return new SkillLookupResult(info, List.of(), null);
        }
    }
}
