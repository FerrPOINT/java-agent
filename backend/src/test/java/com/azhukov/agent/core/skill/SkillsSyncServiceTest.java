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
 * P2-14: Tests for SkillsSyncService — bundled skills auto-copy on first run.
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

        // Should have copied the 5 bundled skills
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
    void syncFromClasspath_doesNothing_whenDirHasSkills() throws IOException {
        SkillsSyncService service = createService();

        // Create skills dir with an existing skill
        Path skillsDir = tempDir.resolve("skills");
        Path existingSkillDir = skillsDir.resolve("my-existing-skill");
        Files.createDirectories(existingSkillDir);
        Files.writeString(existingSkillDir.resolve("SKILL.md"),
            "---\nname: my-existing-skill\ndescription: \"Existing skill\"\n---\n# Existing\n");

        int copied = service.syncFromClasspath(skillsDir);

        // Should not copy anything since dir already has skills
        assertThat(copied).isZero();

        // Verify existing skill is still there
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(skillFiles).hasSize(1);
        assertThat(skillFiles.get(0).getParent().getFileName().toString()).isEqualTo("my-existing-skill");
    }

    @Test
    void syncFromClasspath_doesNotOverwriteExistingFiles() throws IOException {
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

        // Second copy: should NOT overwrite anything
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

        // Second sync — should do nothing since dir now has skills
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
}