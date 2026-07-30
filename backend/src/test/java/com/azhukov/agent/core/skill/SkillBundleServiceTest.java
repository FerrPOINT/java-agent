package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * S6: Tests for SkillBundleService — SnakeYAML parsing, reload, conflict resolution, usage tracking.
 */
class SkillBundleServiceTest {

    @TempDir
    Path tempDir;

    private AgentProperties createProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        return properties;
    }

    private void writeBundle(String filename, String content) throws IOException {
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve(filename), content);
    }

    @Test
    void install_readsBundleAndSavesSkill() throws IOException {
        // Set up bundle directory
        Path bundlesDir = tempDir.resolve("bundles");
        Path bundleDir = bundlesDir.resolve("test-bundle");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("SKILL.md"), "# Test Skill\nThis is a test bundle.");
        Files.createDirectories(bundleDir.resolve("references"));

        AgentProperties properties = createProperties();
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, properties);

        service.install("test-bundle");

        verify(skillManager).saveSkill(eq("test-bundle"), contains("Test Skill"));
    }

    @Test
    void list_returnsSkillNames() {
        AgentProperties properties = createProperties();
        SkillManager skillManager = mock(SkillManager.class);
        var names = java.util.List.of("bundle1", "bundle2");
        when(skillManager.listSkillNames()).thenReturn(names);

        SkillBundleService service = new SkillBundleService(skillManager, properties);

        assertThat(service.list()).isEqualTo(names);
    }

    @Test
    void uninstall_deletesSkill() {
        AgentProperties properties = createProperties();
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.deleteSkill("test-bundle")).thenReturn(true);

        SkillBundleService service = new SkillBundleService(skillManager, properties);

        service.uninstall("test-bundle");

        verify(skillManager).deleteSkill("test-bundle");
    }

    @Test
    void install_throwsWhenBundleNotFound() {
        AgentProperties properties = createProperties();

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, properties);

        assertThatThrownBy(() -> service.install("nonexistent-bundle"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bundle directory not found");
    }

    // S6: New tests for YAML-based bundle parsing with SnakeYAML

    @Test
    void scanBundles_parsesYamlWithSnakeYaml() throws IOException {
        writeBundle("backend-dev.yaml", """
            name: backend-dev
            description: Backend feature work
            skills:
              - github-code-review
              - test-driven-development
            instruction: Extra guidance
            """);

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, createProperties());

        var bundles = service.scanBundles();
        assertThat(bundles).hasSize(1);
        assertThat(bundles).containsKey("/backend-dev");
        var bundle = bundles.get("/backend-dev");
        assertThat(bundle.name()).isEqualTo("backend-dev");
        assertThat(bundle.description()).isEqualTo("Backend feature work");
        assertThat(bundle.skills()).containsExactly("github-code-review", "test-driven-development");
        assertThat(bundle.instruction()).isEqualTo("Extra guidance");
    }

    @Test
    void scanBundles_fallbackToFilenameWhenNoName() throws IOException {
        writeBundle("my-bundle.yaml", """
            description: A bundle without a name
            skills:
              - skill1
            """);

        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        var bundles = service.scanBundles();
        assertThat(bundles).containsKey("/my-bundle");
    }

    @Test
    void scanBundles_skipsBundleWithNoSkills() throws IOException {
        writeBundle("empty.yaml", """
            name: empty-bundle
            description: No skills
            skills: []
            """);

        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        var bundles = service.scanBundles();
        assertThat(bundles).isEmpty();
    }

    @Test
    void scanBundles_skipsInvalidYaml() throws IOException {
        writeBundle("bad.yaml", "this is not: [valid yaml: [[[");
        writeBundle("good.yaml", """
            name: good-bundle
            skills:
              - skill1
            """);

        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        var bundles = service.scanBundles();
        assertThat(bundles).hasSize(1);
        assertThat(bundles).containsKey("/good-bundle");
    }

    @Test
    void resolveBundleCommandKey_handlesUnderscoreToHyphen() throws IOException {
        writeBundle("test-bundle.yaml", """
            name: test-bundle
            skills:
              - skill1
            """);

        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        assertThat(service.resolveBundleCommandKey("test_bundle")).isEqualTo("/test-bundle");
        assertThat(service.resolveBundleCommandKey("test-bundle")).isEqualTo("/test-bundle");
        assertThat(service.resolveBundleCommandKey("nonexistent")).isNull();
    }

    @Test
    void reloadBundles_returnsDiff() throws IOException {
        writeBundle("bundle1.yaml", """
            name: bundle1
            description: First bundle
            skills:
              - skill1
            """);

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, createProperties());

        // First scan
        service.scanBundles();
        // Reload (should show bundle1 as unchanged)
        var diff = service.reloadBundles();
        assertThat(diff.unchanged()).contains("bundle1");
        assertThat(diff.total()).isEqualTo(1);

        // Add a new bundle
        writeBundle("bundle2.yaml", """
            name: bundle2
            description: Second bundle
            skills:
              - skill2
            """);

        var diff2 = service.reloadBundles();
        assertThat(diff2.added()).hasSize(1);
        assertThat(diff2.added().get(0).name()).isEqualTo("bundle2");
        assertThat(diff2.unchanged()).contains("bundle1");
        assertThat(diff2.total()).isEqualTo(2);
    }

    @Test
    void buildBundleInvocationMessage_loadsSkillsFromManager() throws IOException {
        writeBundle("test-bundle.yaml", """
            name: test-bundle
            skills:
              - skill1
              - skill2
            """);

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.getSkill("skill1")).thenReturn("# Skill 1\nContent of skill 1");
        when(skillManager.getSkill("skill2")).thenReturn("# Skill 2\nContent of skill 2");

        SkillBundleService service = new SkillBundleService(skillManager, createProperties());
        var result = service.buildBundleInvocationMessage("/test-bundle", "do something", "session-1");

        assertThat(result).isNotNull();
        assertThat(result.loadedSkillNames()).containsExactly("skill1", "skill2");
        assertThat(result.missingSkillNames()).isEmpty();
        assertThat(result.message()).contains("test-bundle");
        assertThat(result.message()).contains("Skill 1");
        assertThat(result.message()).contains("Skill 2");
        assertThat(result.message()).contains("do something");
    }

    @Test
    void buildBundleInvocationMessage_handlesMissingSkills() throws IOException {
        writeBundle("test-bundle.yaml", """
            name: test-bundle
            skills:
              - skill1
              - missing-skill
            """);

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.getSkill("skill1")).thenReturn("# Skill 1");
        when(skillManager.getSkill("missing-skill")).thenReturn(null);

        SkillBundleService service = new SkillBundleService(skillManager, createProperties());
        var result = service.buildBundleInvocationMessage("/test-bundle", "", "session-1");

        assertThat(result).isNotNull();
        assertThat(result.loadedSkillNames()).containsExactly("skill1");
        assertThat(result.missingSkillNames()).containsExactly("missing-skill");
    }

    @Test
    void buildBundleInvocationMessage_returnsNullForUnknownBundle() {
        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        assertThat(service.buildBundleInvocationMessage("/nonexistent", "", "s1")).isNull();
    }

    @Test
    void installBundle_doesNotPersistSkills() {
        var bundle = new SkillBundleService.Bundle(
            "test-bundle", "desc", java.util.List.of("skill1", "skill2"), "", null
        );
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, createProperties());

        service.installBundle(bundle);

        // S6 FIX: Should NOT call saveSkill for each skill
        verify(skillManager, never()).saveSkill(anyString(), anyString());
    }

    @Test
    void bumpUse_incrementsViewCount() {
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, createProperties());
        service.bumpUse("skill1");
        verify(skillManager).incrementViewCount("skill1");
    }

    @Test
    void deleteBundle_removesFile() throws IOException {
        writeBundle("to-delete.yaml", """
            name: to-delete
            skills:
              - skill1
            """);

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, createProperties());
        service.scanBundles(); // populate cache

        boolean deleted = service.deleteBundle("to-delete");
        assertThat(deleted).isTrue();
    }

    @Test
    void saveBundle_writesYamlFile() {
        var bundle = new SkillBundleService.Bundle(
            "new-bundle", "description", java.util.List.of("skill1"), "instruction", null
        );
        SkillBundleService service = new SkillBundleService(mock(SkillManager.class), createProperties());
        service.saveBundle(bundle);

        Path file = tempDir.resolve("skill-bundles").resolve("new-bundle.yaml");
        assertThat(Files.exists(file)).isTrue();
    }
}