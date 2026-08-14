package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-14 / H10: Tests for SkillsSyncService — manifest-based bundled skills sync.
 */
class SkillsSyncServiceTest {

    @TempDir
    Path tempDir;

    private AgentProperties createProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        return properties;
    }

    private SkillsSyncService createService() {
        return new SkillsSyncService(createProperties());
    }

    @Test
    void syncFromClasspath_copiesBundledSkills_whenDirMissing() throws IOException {
        SkillsSyncService service = createService();

        // Skills dir doesn't exist yet
        Path skillsDir = tempDir.resolve("skills");
        assertThat(Files.notExists(skillsDir)).isTrue();

        int copied = service.syncFromClasspath(skillsDir);

        // Should have copied the bundled skills
        assertThat(copied).isGreaterThan(0);
        assertThat(Files.isDirectory(skillsDir)).isTrue();

        // Verify some skills were copied
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();
    }

    @Test
    void syncFromClasspath_copiesBundledSkills_whenDirEmpty() throws IOException {
        SkillsSyncService service = createService();

        // Create empty skills dir
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        assertThat(Files.isDirectory(skillsDir)).isTrue();

        int copied = service.syncFromClasspath(skillsDir);

        assertThat(copied).isGreaterThan(0);

        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();
    }

    @Test
    void syncFromClasspath_doesNotCopy_whenDirHasSkillsFromManifest() throws IOException {
        SkillsSyncService service = createService();

        // First sync: copy all bundled skills
        Path skillsDir = tempDir.resolve("skills");
        int firstCopy = service.syncFromClasspath(skillsDir);
        assertThat(firstCopy).isGreaterThan(0);

        // Second sync: should not copy anything (all in manifest, unchanged)
        int secondCopy = service.syncFromClasspath(skillsDir);
        assertThat(secondCopy).isZero();

        // Verify skills are still there
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();
    }

    @Test
    void syncFromClasspath_doesNotOverwriteUserModifiedFiles() throws IOException {
        SkillsSyncService service = createService();

        // First copy: should copy all bundled skills
        Path skillsDir = tempDir.resolve("skills");
        int firstCopy = service.syncFromClasspath(skillsDir);
        assertThat(firstCopy).isGreaterThan(0);

        // Record one skill's content
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        Path firstSkill = skillFiles.get(0);
        String originalContent = Files.readString(firstSkill);

        // Modify the skill file (simulating user customization)
        String modifiedContent = originalContent + "\n<!-- user customization -->\n";
        Files.writeString(firstSkill, modifiedContent);

        // Second copy: should NOT overwrite anything (hash differs → user modified)
        int secondCopy = service.syncFromClasspath(skillsDir);
        assertThat(secondCopy).isZero();

        // Verify the file was not overwritten
        String afterContent = Files.readString(firstSkill);
        assertThat(afterContent).isEqualTo(modifiedContent);
    }

    @Test
    void syncFromClasspath_isIdempotent() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");

        // First sync
        int firstCopy = service.syncFromClasspath(skillsDir);
        assertThat(firstCopy).isGreaterThan(0);

        // Second sync — should do nothing since all skills are in manifest and unchanged
        int secondCopy = service.syncFromClasspath(skillsDir);
        assertThat(secondCopy).isZero();
    }

    @Test
    void syncFromClasspath_copiesToCorrectNestedStructure() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");
        int copied = service.syncFromClasspath(skillsDir);
        assertThat(copied).isGreaterThan(0);

        // Verify nested directory structure is preserved
        // e.g., skills/software-development/plan/SKILL.md
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        for (Path skillFile : skillFiles) {
            String relative = skillsDir.relativize(skillFile).toString();
            // Should contain at least one directory separator (category/name/SKILL.md)
            assertThat(relative).contains("/");
            assertThat(skillFile.getFileName().toString()).isEqualTo("SKILL.md");
        }
    }

    @Test
    void syncFromClasspath_copiesValidFrontmatter() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");
        service.syncFromClasspath(skillsDir);

        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();

        for (Path skillFile : skillFiles) {
            String content = Files.readString(skillFile);
            // Each skill should have valid frontmatter
            assertThat(content).startsWith("---");
            SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
            assertThat(fr.frontmatter()).containsKey("name");
            assertThat(fr.frontmatter()).containsKey("description");
        }
    }

    @Test
    void syncBundledSkills_runsOnPostConstruct() throws IOException {
        // Test that @PostConstruct sync works end-to-end
        SkillsSyncService service = createService();

        // Simulate @PostConstruct call
        service.syncBundledSkills();

        Path skillsDir = tempDir.resolve("skills");
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();
    }

    // ── H10: Manifest-based sync tests ──

    @Test
    void syncFromClasspath_writesManifestAfterSync() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");
        service.syncFromClasspath(skillsDir);

        Path manifest = skillsDir.resolve(".bundled-manifest");
        assertThat(Files.exists(manifest)).isTrue();
        String manifestContent = Files.readString(manifest);
        assertThat(manifestContent).isNotEmpty();
        // Manifest should be valid JSON (contains at least one entry)
        assertThat(manifestContent).contains("\"");
    }

    @Test
    void syncFromClasspath_doesNotReAddUserDeletedSkills() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");

        // First sync: copy all bundled skills
        int firstCopy = service.syncFromClasspath(skillsDir);
        assertThat(firstCopy).isGreaterThan(0);

        // Delete one skill file (simulate user deletion)
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).isNotEmpty();
        Path skillToDelete = skillFiles.get(0);
        Files.delete(skillToDelete);
        assertThat(Files.exists(skillToDelete)).isFalse();

        // Second sync: should NOT re-add the deleted skill
        int secondCopy = service.syncFromClasspath(skillsDir);
        assertThat(secondCopy).isZero();

        // Verify the deleted file was not re-created
        assertThat(Files.exists(skillToDelete)).isFalse();
    }

    @Test
    void syncFromClasspath_copiesNewBundledSkillsAddedLater() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");

        // First sync: copy all bundled skills
        int firstCopy = service.syncFromClasspath(skillsDir);
        assertThat(firstCopy).isGreaterThan(0);

        // Simulate adding a new bundled skill by creating a file that's not in manifest
        // (This tests the "not in manifest, not on disk → copy" path indirectly)
        // Since we can't add to classpath at runtime, we verify the manifest logic:
        // Delete manifest → re-run sync → all existing files are treated as non-manifest → skip
        Files.delete(skillsDir.resolve(".bundled-manifest"));

        // Delete one skill to simulate a new one needing copy
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        Path skillToReadd = skillFiles.get(0);
        String skillRelativePath = skillsDir.relativize(skillToReadd).toString();
        Files.delete(skillToReadd);

        // Re-sync: the deleted skill is not in manifest (we deleted it) and not on disk → copy
        int reCopy = service.syncFromClasspath(skillsDir);
        assertThat(reCopy).isEqualTo(1);
        assertThat(Files.exists(skillToReadd)).isTrue();
    }

    @Test
    void syncFromClasspath_preservesUserCreatedSkills() throws IOException {
        SkillsSyncService service = createService();

        Path skillsDir = tempDir.resolve("skills");

        // First sync
        service.syncFromClasspath(skillsDir);

        // User creates their own skill (not in manifest)
        Path userSkillDir = skillsDir.resolve("my-custom-skill");
        Files.createDirectories(userSkillDir);
        Path userSkillFile = userSkillDir.resolve("SKILL.md");
        String userContent = "---\nname: my-custom-skill\ndescription: \"Custom\"\n---\n# Custom\n";
        Files.writeString(userSkillFile, userContent);

        // Second sync: should not overwrite or delete the user's custom skill
        int secondCopy = service.syncFromClasspath(skillsDir);
        // It might copy 0 new skills since user skill is not in bundled resources
        assertThat(secondCopy).isZero();

        // User skill should still be intact
        assertThat(Files.readString(userSkillFile)).isEqualTo(userContent);
    }
}