package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.ProfileInstructions;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProfileInstructionsPromptTest {
    @TempDir Path tempDir;
    private ProfileService profiles;
    private DefaultPromptBuilder builder;
    private Path base;

    @BeforeEach
    void setUp() throws Exception {
        var properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Files.writeString(tempDir.resolve("AGENTS.md"), "outside-global-project");
        Path globalPersona = tempDir.resolve("global-soul.md");
        Files.writeString(globalPersona, "outside-global-persona");
        properties.getCore().setSoulMdPath(globalPersona.toString());
        profiles = new ProfileService(properties, new RuntimeConfigService());
        builder = new DefaultPromptBuilder(properties, mock(ToolRegistry.class));
        var mapper = new ObjectMapper();
        ReflectionTestUtils.setField(builder, "profileInstructions", new ProfileInstructions(properties, profiles, mapper));

        base = tempDir.resolve("base");
        Files.createDirectories(base.resolve("roles"));
        String expectedRole = "base-team-rules";
        Files.writeString(base.resolve("roles/developer.md"), expectedRole);
        String manifest = mapper.writeValueAsString(Map.of(
            "schema", "base-hermes-role-skills/v1",
            "roles", Map.of("developer", Map.of("roleInstruction", Map.of(
                "path", "roles/developer.md", "sha256", digest(expectedRole))))
        ));
        Files.writeString(base.resolve("manifest.json"), manifest);
        properties.getProfile().setBaseInstructionsDir(base.toString());
        properties.getProfile().setBaseInstructionsManifestSha256(digest(manifest));
    }

    @Test
    void readsOnlyTheSelectedProfileAndSharedRoleInDocumentedOrder() throws Exception {
        setupProfile("alpha", true);
        setupProfile("beta", true);
        var sameUser = Session.create("same-user", "openai", "fixture");
        var a = sameUser.withMetadata("profile", "alpha");
        var b = sameUser.withMetadata("profile", "beta");

        String alpha = builder.buildSystemMessage(a).content();
        String beta = builder.buildSystemMessage(b).content();
        assertThat(alpha).containsPattern("(?s)base-team-rules.*alpha-persona.*alpha-project.*alpha-override");
        assertThat(beta).containsPattern("(?s)base-team-rules.*beta-persona.*beta-project.*beta-override");
        assertThat(alpha).doesNotContain("beta-persona", "beta-project", "outside-global");
        assertThat(beta).doesNotContain("alpha-persona", "alpha-project", "outside-global");

        profiles.writeSoul("alpha", "alpha-persona-updated");
        String alphaUpdated = builder.buildSystemMessage(a).content();
        assertThat(alphaUpdated).contains("alpha-persona-updated");
        assertThat(builder.buildSystemMessage(b).content()).contains("beta-persona").doesNotContain("alpha-persona-updated");
    }

    @Test
    void rejectsAChangedPinnedRoleAndCrossProfileLinks() throws Exception {
        setupProfile("alpha", true);
        setupProfile("beta", false);
        var a = Session.create("same-user", "openai", "fixture").withMetadata("profile", "alpha");
        var b = a.withMetadata("profile", "beta");
        assertThat(builder.buildSystemMessage(b).content()).contains("beta-persona").doesNotContain("base-team-rules");

        Files.writeString(base.resolve("roles/developer.md"), "changed-base-text");
        assertThatThrownBy(() -> builder.buildSystemMessage(a)).isInstanceOf(IllegalStateException.class);
        assertThat(builder.buildSystemMessage(b).content()).doesNotContain("changed-base-text");

        Files.delete(profiles.workspacePath("beta").resolve("AGENTS.md"));
        Files.createSymbolicLink(profiles.workspacePath("beta").resolve("AGENTS.md"),
            profiles.workspacePath("alpha").resolve("AGENTS.md"));
        assertThatThrownBy(() -> builder.buildSystemMessage(b)).isInstanceOf(IllegalStateException.class);

        Files.delete(profiles.soulPath("beta"));
        Files.createSymbolicLink(profiles.soulPath("beta"), profiles.soulPath("alpha"));
        assertThatThrownBy(() -> profiles.writeSoul("beta", "foreign-update")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(profiles.soulPath("alpha"))).isEqualTo("alpha-persona");
    }

    private void setupProfile(String name, boolean withBase) throws Exception {
        Files.createDirectories(profiles.workspacePath(name));
        profiles.writeSoul(name, name + "-persona");
        Files.writeString(profiles.workspacePath(name).resolve("AGENTS.md"), name + "-project");
        Files.writeString(profiles.workspacePath(name).resolve("AGENTS.override.md"), name + "-override");
        if (withBase) {
            profiles.writeRawConfig(name, "instructions:\n  base_role: developer\n");
        }
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
