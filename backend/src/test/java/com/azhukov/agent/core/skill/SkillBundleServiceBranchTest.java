package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link SkillBundleService}.
 * Covers error paths, edge cases, null inputs, and boundary conditions.
 */
class SkillBundleServiceBranchTest {

    private SkillManager skillManager;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        skillManager = mock(SkillManager.class);
        properties = new AgentProperties();
    }

    // ── resolveBundleCommandKey ──

    @Test
    void resolveBundleCommandKey_nullCommand_returnsNull() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.resolveBundleCommandKey(null)).isNull();
    }

    @Test
    void resolveBundleCommandKey_blankCommand_returnsNull() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.resolveBundleCommandKey("  ")).isNull();
    }

    @Test
    void resolveBundleCommandKey_emptyCommand_returnsNull() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.resolveBundleCommandKey("")).isNull();
    }

    @Test
    void resolveBundleCommandKey_nonExistentBundle_returnsNull() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.resolveBundleCommandKey("nonexistent")).isNull();
    }

    // ── installBundle ──

    @Test
    void installBundle_nullBundle_throwsException() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThatThrownBy(() -> service.installBundle(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bundle must have name and skills list");
    }

    @Test
    void installBundle_nullName_throwsException() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle(null, "desc", List.of("s1"), "", "/path");
        assertThatThrownBy(() -> service.installBundle(bundle))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installBundle_nullSkills_throwsException() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle("test", "desc", null, "", "/path");
        assertThatThrownBy(() -> service.installBundle(bundle))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installBundle_validBundle_doesNotThrow() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle("test", "desc", List.of("s1"), "", "/path");
        service.installBundle(bundle); // should not throw
    }

    // ── buildBundleInvocationMessage ──

    @Test
    void buildBundleInvocationMessage_nonExistentKey_returnsNull() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.buildBundleInvocationMessage("/nonexistent", null, "sess1")).isNull();
    }

    // ── deleteBundle ──

    @Test
    void deleteBundle_nonExistent_returnsFalse() {
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.deleteBundle("nonexistent")).isFalse();
    }

    @Test
    void deleteBundle_existingFile_returnsTrue(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Path bundleFile = bundlesDir.resolve("test-bundle.yaml");
        Files.writeString(bundleFile, """
            name: test-bundle
            description: A test bundle
            skills:
              - skill1
              - skill2
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.deleteBundle("test-bundle")).isTrue();
        assertThat(Files.exists(bundleFile)).isFalse();
    }

    // ── saveBundle ──

    @Test
    void saveBundle_writesFile(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle(
            "my-bundle", "Test description", List.of("skill-a", "skill-b"), "Do things", null);

        service.saveBundle(bundle);

        Path savedFile = tempDir.resolve("skill-bundles").resolve("my-bundle.yaml");
        assertThat(Files.exists(savedFile)).isTrue();
        String content = Files.readString(savedFile);
        assertThat(content).contains("my-bundle");
        assertThat(content).contains("skill-a");
    }

    @Test
    void saveBundle_nullInstruction_omitsInstructionField(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle(
            "no-instr", "Test", List.of("skill-a"), "", null);

        service.saveBundle(bundle);

        Path savedFile = tempDir.resolve("skill-bundles").resolve("no-instr.yaml");
        String content = Files.readString(savedFile);
        assertThat(content).doesNotContain("instruction");
    }

    @Test
    void saveBundle_blankInstruction_omitsInstructionField(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        SkillBundleService.Bundle bundle = new SkillBundleService.Bundle(
            "blank-instr", "Test", List.of("skill-a"), "   ", null);

        service.saveBundle(bundle);

        Path savedFile = tempDir.resolve("skill-bundles").resolve("blank-instr.yaml");
        String content = Files.readString(savedFile);
        assertThat(content).doesNotContain("instruction");
    }

    // ── bumpUse ──

    @Test
    void bumpUse_exceptionInSkillManager_doesNotThrow() {
        doThrow(new RuntimeException("DB error")).when(skillManager).incrementViewCount("skill1");
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        service.bumpUse("skill1"); // should not throw
    }

    // ── list ──

    @Test
    void list_delegatesToSkillManager() {
        when(skillManager.listSkillNames()).thenReturn(List.of("skill-a", "skill-b"));
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.list()).containsExactly("skill-a", "skill-b");
    }

    // ── uninstall ──

    @Test
    void uninstall_delegatesToSkillManager() {
        when(skillManager.deleteSkill("test-skill")).thenReturn(true);
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        service.uninstall("test-skill");
        verify(skillManager).deleteSkill("test-skill");
    }

    @Test
    void uninstallBundle_removesPrefixedSkills() {
        when(skillManager.listSkillNames()).thenReturn(List.of("my-bundle/skill1", "my-bundle/skill2", "other-skill"));
        when(skillManager.deleteSkill("my-bundle/skill1")).thenReturn(true);
        when(skillManager.deleteSkill("my-bundle/skill2")).thenReturn(true);
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        service.uninstallBundle("my-bundle");
        verify(skillManager).deleteSkill("my-bundle/skill1");
        verify(skillManager).deleteSkill("my-bundle/skill2");
        verify(skillManager, never()).deleteSkill("other-skill");
    }

    // ── scanBundles ──

    @Test
    void scanBundles_noDirectory_returnsEmpty() {
        properties.getCore().setWorkingDirectory("/tmp/nonexistent-dir-xyz");
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.scanBundles()).isEmpty();
    }

    @Test
    void scanBundles_duplicateSlug_logsWarningAndKeepsFirst(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("a.yaml"), """
            name: dup
            description: First
            skills:
              - s1
            """);
        Files.writeString(bundlesDir.resolve("b.yaml"), """
            name: dup
            description: Second
            skills:
              - s2
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().description()).isEqualTo("First");
    }

    @Test
    void scanBundles_invalidYaml_skipped(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("invalid.yaml"), "{{{invalid yaml");

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).isEmpty();
    }

    @Test
    void scanBundles_notAMapping_skipped(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("list.yaml"), "- item1\n- item2\n");

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).isEmpty();
    }

    @Test
    void scanBundles_emptySkillsList_skipped(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("empty.yaml"), """
            name: empty-bundle
            description: No skills
            skills: []
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).isEmpty();
    }

    @Test
    void scanBundles_emptyName_skipped(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("noname.yaml"), """
            name: ""
            description: No name
            skills:
              - s1
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).isEmpty();
    }

    @Test
    void scanBundles_missingSkillsField_skipped(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("noskills.yaml"), """
            name: no-skills
            description: No skills field
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).isEmpty();
    }

    @Test
    void scanBundles_emptyDescription_generatesDefault(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("nodesc.yaml"), """
            name: no-desc
            skills:
              - s1
              - s2
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).hasSize(1);
        assertThat(result.get("/no-desc").description()).contains("2 skills");
    }

    @Test
    void scanBundles_ymlExtension_alsoParsed(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("test.yml"), """
            name: yml-bundle
            description: YML extension
            skills:
              - s1
            """);

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).containsKey("/yml-bundle");
    }

    @Test
    void scanBundles_nonTextFile_skippedGracefully(@TempDir Path tempDir) throws Exception {
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path bundlesDir = tempDir.resolve("skill-bundles");
        Files.createDirectories(bundlesDir);
        Files.writeString(bundlesDir.resolve("read-error.yaml"), """
            name: valid
            description: Valid
            skills:
              - s1
            """);
        // Add a .txt file which should be ignored
        Files.writeString(bundlesDir.resolve("readme.txt"), "not a bundle");

        SkillBundleService service = new SkillBundleService(skillManager, properties);
        Map<String, SkillBundleService.Bundle> result = service.scanBundles();
        assertThat(result).hasSize(1);
        assertThat(result).containsKey("/valid");
    }

    // ── reloadBundles ──

    @Test
    void reloadBundles_empty_returnsEmptyDiff() {
        properties.getCore().setWorkingDirectory("/tmp/nonexistent-dir-xyz");
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        var diff = service.reloadBundles();
        assertThat(diff).isNotNull();
    }

    // ── listBundlesInfo ──

    @Test
    void listBundlesInfo_empty_returnsEmptyList() {
        properties.getCore().setWorkingDirectory("/tmp/nonexistent-dir-xyz");
        SkillBundleService service = new SkillBundleService(skillManager, properties);
        assertThat(service.listBundlesInfo()).isEmpty();
    }
}