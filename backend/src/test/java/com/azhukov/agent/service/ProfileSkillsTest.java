package com.azhukov.agent.service;

import com.azhukov.agent.api.SkillsDiscoveryController;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.memory.SkillManageTool;
import com.azhukov.agent.tools.memory.SkillViewTool;
import com.azhukov.agent.tools.memory.SkillsListTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileSkillsTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillManager global = mock(SkillManager.class);
    private ProfileService profiles;
    private ProfileSkills scopes;
    private Path base;
    private SkillsListTool list;
    private SkillViewTool view;
    private SkillManageTool manage;
    private final Session alpha = Session.create("operator", "openai", "same").withMetadata("profile", "alpha");
    private final Session beta = alpha.withMetadata("profile", "beta");

    @BeforeEach
    void setUp() throws Exception {
        var properties = new AgentProperties();
        properties.getProfile().setBaseDir(temp.resolve("profiles").toString());
        profiles = new ProfileService(properties, new RuntimeConfigService());
        for (String profile : List.of("alpha", "beta")) {
            Files.createDirectories(profiles.workspacePath(profile));
            profiles.writeRawConfig(profile, "instructions:\n  base_role: developer\n");
        }
        base = temp.resolve("base");
        Files.createDirectories(base.resolve("roles"));
        Files.createDirectories(base.resolve("skills/shared-review"));
        Files.createDirectories(base.resolve("skills/private-plan"));
        String shared = skill("shared-review", "Shared review procedure.");
        String other = skill("private-plan", "Another role procedure.");
        String role = "Shared developer instructions.";
        Files.writeString(base.resolve("roles/developer.md"), role);
        Files.writeString(base.resolve("skills/shared-review/SKILL.md"), shared);
        Files.writeString(base.resolve("skills/private-plan/SKILL.md"), other);
        String manifest = mapper.writeValueAsString(Map.of(
            "schema", "base-hermes-role-skills/v1",
            "sources", Map.of("native", Map.of("skills", Map.of("shared-review", digest(shared), "private-plan", digest(other)))),
            "roles", Map.of("developer", Map.of(
                "roleInstruction", Map.of("path", "roles/developer.md", "sha256", digest(role)),
                "physicalSkills", List.of("shared-review")))
        ));
        Files.writeString(base.resolve("manifest.json"), manifest);
        properties.getProfile().setBaseInstructionsDir(base.toString());
        properties.getProfile().setBaseInstructionsManifestSha256(digest(manifest));
        scopes = new ProfileSkills(profiles, new ProfileInstructions(properties, profiles, mapper));
        list = bind(new SkillsListTool(global));
        view = bind(new SkillViewTool(global));
        manage = bind(new SkillManageTool(global, mock(com.azhukov.agent.core.skill.SkillMutationLedger.class)));
    }

    @Test
    void seesOnlyOwnSkillsAndThePinnedRoleSetAcrossToolsAndDiscovery() throws Exception {
        assertThat(call(manage, alpha, Map.of("action", "create", "name", "alpha-note",
            "content", skill("alpha-note", "Alpha procedure."))).success()).isTrue();
        assertThat(call(manage, alpha, Map.of("action", "write_file", "name", "alpha-note",
            "file_path", "references/note.md", "content", "Alpha handbook.")).success()).isTrue();
        assertThat(call(view, alpha, Map.of("name", "alpha-note")).content()).contains("references/note.md");
        assertThat(call(view, alpha, Map.of("name", "alpha-note", "file_path", "references/note.md")).content())
            .isEqualTo("Alpha handbook.");
        assertThat(call(view, beta, Map.of("name", "alpha-note", "file_path", "references/note.md")).success()).isFalse();
        assertThat(call(view, beta, Map.of("name", "alpha-note")).success()).isFalse();
        assertThat(call(manage, beta, Map.of("action", "edit", "name", "alpha-note",
            "content", skill("alpha-note", "Wrong owner."))).success()).isFalse();
        assertThat(call(manage, beta, Map.of("action", "delete", "name", "alpha-note")).success()).isFalse();
        assertThat(call(view, alpha, Map.of("name", "alpha-note")).content()).contains("Alpha procedure.");
        assertThat(call(manage, beta, Map.of("action", "create", "name", "beta-note",
            "content", skill("beta-note", "Beta procedure."))).success()).isTrue();
        assertThat(call(list, beta, Map.of()).content()).contains("beta-note", "shared-review")
            .doesNotContain("alpha-note", "private-plan");

        var controller = bind(new SkillsDiscoveryController(global));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/p/alpha/v1/skills")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].name").value("alpha-note"))
            .andExpect(jsonPath("$.data[1].name").value("shared-review"));
        mvc.perform(get("/p/beta/v1/skills")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("beta-note"));
        verifyNoInteractions(global);
    }

    @Test
    void rejectsBaseWritesAndTamperingAndKeepsTheProfileOptInIndependent() throws Exception {
        assertThat(call(view, alpha, Map.of("name", "shared-review")).content()).contains("Shared review procedure.");
        assertThat(call(view, beta, Map.of("name", "shared-review")).content()).contains("Shared review procedure.");
        assertThat(call(view, beta, Map.of("name", "private-plan")).success()).isFalse();
        assertThat(call(manage, beta, Map.of("action", "edit", "name", "shared-review",
            "content", skill("shared-review", "Changed review."))).error()).contains("read-only");
        assertThat(call(manage, beta, Map.of("action", "delete", "name", "shared-review")).error()).contains("read-only");
        assertThat(Files.readString(base.resolve("skills/shared-review/SKILL.md"))).contains("Shared review procedure.");

        scopes.setEnabled("beta", "shared-review", false);
        assertThat(call(view, beta, Map.of("name", "shared-review")).success()).isFalse();
        assertThat(call(view, alpha, Map.of("name", "shared-review")).success()).isTrue();
        profiles.writeRawConfig("beta", "skills: {}\n");
        assertThat(call(list, beta, Map.of()).content()).doesNotContain("shared-review");

        Files.writeString(base.resolve("skills/shared-review/SKILL.md"), "Broken pin");
        assertThatThrownBy(() -> scopes.forProfile("alpha").getSkill("shared-review"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned Base skill");
        assertThat(call(list, beta, Map.of()).success()).isTrue();
        verifyNoInteractions(global);
    }

    @Test
    void doesNotReadOrReplaceFilesLinkedToAnotherProfile() throws Exception {
        var alphaSkills = scopes.forProfile("alpha");
        Path original = alphaSkills.create("alpha-note", skill("alpha-note", "Alpha only."));
        Path betaFile = profiles.profilePath("beta").resolve("skills/alpha-note/SKILL.md");
        Files.createDirectories(betaFile.getParent());
        Files.createSymbolicLink(betaFile, original);
        assertThat(call(view, beta, Map.of("name", "alpha-note")).success()).isFalse();
        assertThat(call(manage, beta, Map.of("action", "create", "name", "alpha-note",
            "content", skill("alpha-note", "Would replace."))).success()).isFalse();
        assertThat(Files.readString(original)).contains("Alpha only.");

        Path betaConfig = profiles.configPath("beta");
        Files.delete(betaConfig);
        Files.createSymbolicLink(betaConfig, profiles.configPath("alpha"));
        assertThatThrownBy(() -> profiles.writeConfig("beta", Map.of("skills", Map.of("disabled", List.of("shared-review")))))
            .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(profiles.configPath("alpha"))).doesNotContain("disabled");
    }

    private com.azhukov.agent.core.model.ToolResult call(ToolHandler tool, Session profile, Map<String, String> args)
            throws Exception {
        return tool.execute(mapper.writeValueAsString(args), null, profile);
    }

    private <T> T bind(T component) {
        ReflectionTestUtils.setField(component, "profileSkills", scopes);
        return component;
    }

    private static String skill(String name, String body) {
        return "---\nname: " + name + "\ndescription: Review fixture\n---\n" + body + "\n";
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
