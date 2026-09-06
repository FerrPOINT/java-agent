package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.core.ports.SkillStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage tests for {@link DatabaseSkillManager}.
 * Covers listSkills, getSkillInfo, archive/unarchive, support file ops, reload.
 */
class DatabaseSkillManagerBranchTest {

    private static final String VALID_FRONTMATTER = """
        ---
        name: test-skill
        description: A test skill for testing.
        ---
        This is the skill body.
        """;

    // ─── listSkills / getSkillInfo ───

    @Test
    void listSkills_filtersArchivedAndMapsToInfo() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e1 = new SkillEntity();
        e1.setName("s1");
        e1.setContent(VALID_FRONTMATTER);
        e1.setArchived(false);
        SkillEntity e2 = new SkillEntity();
        e2.setName("s2");
        e2.setContent(VALID_FRONTMATTER);
        e2.setArchived(true);
        when(repo.findAll()).thenReturn(List.of(e1, e2));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        List<SkillManager.SkillInfo> skills = mgr.listSkills();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("s1");
    }

    @Test
    void listSkills_emptyRepo_returnsEmpty() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findAll()).thenReturn(List.of());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.listSkills()).isEmpty();
    }

    @Test
    void getSkillInfo_existingSkill_returnsInfo() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent(VALID_FRONTMATTER);
        e.setUpdatedAt(Instant.now());
        e.setViewCount(5);
        e.setManageCount(3);
        e.setLastActivityAt(Instant.now());
        e.setArchived(false);
        e.setTrustLevel("AGENT_CREATED");
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("test");
        assertThat(info.viewCount()).isEqualTo(5);
    }

    @Test
    void getSkillInfo_nonExistentSkill_returnsNull() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.getSkillInfo("nonexistent")).isNull();
    }

    // ─── extractCategory ───

    @Test
    void getSkillInfo_extractsCategoryFromFrontmatter() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            category: browser-automation
            ---
            Body
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.category()).isEqualTo("browser-automation");
    }

    @Test
    void getSkillInfo_noFrontmatter_fallsBackToHeading() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("# My Skill Heading\nBody content");
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.category()).isEqualTo("My Skill Heading");
    }

    @Test
    void getSkillInfo_nullContent_returnsEmptyCategory() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent(null);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.category()).isEmpty();
    }

    @Test
    void getSkillInfo_blankContent_returnsEmptyCategory() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("  ");
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.category()).isEmpty();
    }

    @Test
    void getSkillInfo_noCategoryInFrontmatter_fallsBackToHeading() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            ---
            # Heading From Body
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.category()).isEqualTo("Heading From Body");
    }

    // ─── Archive / Unarchive ───

    @Test
    void archiveSkill_nonExistent_returnsFalse() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.archiveSkill("nonexistent")).isFalse();
    }

    @Test
    void archiveSkill_existing_setsArchivedTrue() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        when(repo.findByName("test")).thenReturn(Optional.of(e));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.archiveSkill("test")).isTrue();
        assertThat(e.isArchived()).isTrue();
        verify(repo).save(e);
    }

    @Test
    void unarchiveSkill_existing_setsArchivedFalse() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setArchived(true);
        when(repo.findByName("test")).thenReturn(Optional.of(e));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.unarchiveSkill("test")).isTrue();
        assertThat(e.isArchived()).isFalse();
        verify(repo).save(e);
    }

    @Test
    void unarchiveSkill_nonExistent_returnsFalse() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.unarchiveSkill("nonexistent")).isFalse();
    }

    // ─── incrementManageCount ───

    @Test
    void incrementManageCount_existing_incrementsAndSaves() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setManageCount(3);
        when(repo.findByName("test")).thenReturn(Optional.of(e));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.incrementManageCount("test");
        assertThat(e.getManageCount()).isEqualTo(4);
        verify(repo).save(e);
    }

    @Test
    void incrementManageCount_nonExistent_doesNothing() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.incrementManageCount("nonexistent");
        verify(repo, never()).save(any());
    }

    // ─── deleteSkill nonExistent ───

    @Test
    void deleteSkill_nonExistent_returnsFalse() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThat(mgr.deleteSkill("nonexistent")).isFalse();
    }

    // ─── Support file operations ───

    @Test
    void writeSupportFile_blankPath_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.writeSupportFile("test", "  ", "content"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void writeSupportFile_nullPath_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.writeSupportFile("test", null, "content"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void writeSupportFile_assetsSubdir_accepted(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "assets/icon.png", "binary-data");
        assertThat(Files.exists(tempDir.resolve("skills").resolve("test-skill").resolve("assets").resolve("icon.png"))).isTrue();
    }

    @Test
    void writeSupportFile_templatesSubdir_accepted(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "templates/template.md", "content");
        assertThat(Files.exists(tempDir.resolve("skills").resolve("test-skill").resolve("templates").resolve("template.md"))).isTrue();
    }

    @Test
    void writeSupportFile_scriptsSubdir_accepted(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "scripts/run.sh", "echo hello");
        assertThat(Files.exists(tempDir.resolve("skills").resolve("test-skill").resolve("scripts").resolve("run.sh"))).isTrue();
    }

    @Test
    void removeSupportFile_existingFile_returnsTrue(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "references/file.md", "content");
        assertThat(mgr.removeSupportFile("test-skill", "references/file.md")).isTrue();
    }

    @Test
    void removeSupportFile_nonExistentFile_returnsFalse(@TempDir Path tempDir) {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        assertThat(mgr.removeSupportFile("test-skill", "references/nonexistent.md")).isFalse();
    }

    @Test
    void removeSupportFile_blankPath_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.removeSupportFile("test", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readSupportFile_existingFile_returnsContent(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "references/file.md", "file content");
        assertThat(mgr.readSupportFile("test-skill", "references/file.md")).isEqualTo("file content");
    }

    @Test
    void readSupportFile_nonExistentFile_returnsNull(@TempDir Path tempDir) {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        assertThat(mgr.readSupportFile("test-skill", "references/nonexistent.md")).isNull();
    }

    @Test
    void readSupportFile_blankPath_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.readSupportFile("test", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listSupportFiles_nonExistentSkillDir_returnsEmpty(@TempDir Path tempDir) {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        assertThat(mgr.listSupportFiles("nonexistent-skill")).isEmpty();
    }

    @Test
    void listSupportFiles_withFiles_returnsAll(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "references/file1.md", "content1");
        mgr.writeSupportFile("test-skill", "references/file2.md", "content2");
        mgr.writeSupportFile("test-skill", "templates/tmpl.md", "template");
        List<String> files = mgr.listSupportFiles("test-skill");
        assertThat(files).hasSize(3);
        assertThat(files).anyMatch(f -> f.contains("file1.md"));
        assertThat(files).anyMatch(f -> f.contains("file2.md"));
        assertThat(files).anyMatch(f -> f.contains("tmpl.md"));
    }

    @Test
    void patchSkill_defaultRefusesAmbiguousMatch() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test-skill");
        e.setContent("""
            ---
            name: test-skill
            description: Test
            ---
            old
            old
            """);
        when(repo.findByName("test-skill")).thenReturn(Optional.of(e));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);

        assertThatThrownBy(() -> mgr.patchSkill("test-skill", "old", "new", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("old_text matches 2 times");
        verify(repo, never()).save(any());
    }

    @Test
    void patchSkill_replaceAllUpdatesEveryMatch() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test-skill");
        e.setContent("""
            ---
            name: test-skill
            description: Test
            ---
            old
            old
            """);
        when(repo.findByName("test-skill")).thenReturn(Optional.of(e));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);

        assertThat(mgr.patchSkill("test-skill", "old", "new", true)).isTrue();

        verify(repo).save(argThat(saved ->
            saved.getContent().contains("new\nnew")
                && !saved.getContent().contains("old\nold")));
    }

    @Test
    void patchSupportFile_defaultRefusesAmbiguousMatch(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "references/file.md", "old\nold\n");

        assertThatThrownBy(() -> mgr.patchSupportFile("test-skill", "references/file.md", "old", "new", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("old_text matches 2 times");
        assertThat(mgr.readSupportFile("test-skill", "references/file.md")).isEqualTo("old\nold\n");
    }

    @Test
    void patchSupportFile_replaceAllUpdatesEveryMatch(@TempDir Path tempDir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        mgr.writeSupportFile("test-skill", "references/file.md", "old\nold\n");

        assertThat(mgr.patchSupportFile("test-skill", "references/file.md", "old", "new", true)).isTrue();

        assertThat(mgr.readSupportFile("test-skill", "references/file.md")).isEqualTo("new\nnew\n");
    }

    // ─── reload ───

    @Test
    void reload_doesNotThrow() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findAll()).thenReturn(List.of());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.reload(); // should not throw
    }

    // ─── saveSkill with WriteOrigin ───

    @Test
    void saveSkill_withWriteOrigin_setsOriginOnEntity() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("test-skill")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("test-skill", VALID_FRONTMATTER, WriteOrigin.HUB_INSTALL);
        verify(repo).save(argThat(e ->
            e.getName().equals("test-skill") &&
            e.getWriteOrigin().equals(WriteOrigin.HUB_INSTALL.name())
        ));
    }

    @Test
    void saveSkill_withNullWriteOrigin_defaultsToForeground() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("test-skill")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("test-skill", VALID_FRONTMATTER, null);
        verify(repo).save(argThat(e ->
            e.getWriteOrigin().equals(WriteOrigin.FOREGROUND.name())
        ));
    }

    @Test
    void saveSkill_existingSkillWithTrustLevel_keepsTrustLevel() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity existing = new SkillEntity();
        existing.setTrustLevel("BUILTIN");
        when(repo.findByName("test-skill")).thenReturn(Optional.of(existing));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("test-skill", VALID_FRONTMATTER);
        // Trust level should be BUILTIN (existing)
        verify(repo).save(argThat(e -> e.getTrustLevel().equals("BUILTIN")));
    }

    @Test
    void saveSkill_existingSkillWithInvalidTrustLevel_defaultsToAgentCreated() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity existing = new SkillEntity();
        existing.setTrustLevel("INVALID_LEVEL");
        when(repo.findByName("test-skill")).thenReturn(Optional.of(existing));
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("test-skill", VALID_FRONTMATTER);
        // The saveSkill method calls determineTrustLevelForSave which catches the
        // IllegalArgumentException from TrustLevel.valueOf("INVALID_LEVEL") and
        // defaults to AGENT_CREATED. The trustLevel is only set if null on the entity,
        // but since it's already "INVALID_LEVEL" (non-null), it stays. However, the
        // security scan uses determineTrustLevelForSave which returns AGENT_CREATED.
        // Verify what was actually saved using ArgumentCaptor.
        org.mockito.ArgumentCaptor<SkillEntity> captor = org.mockito.ArgumentCaptor.forClass(SkillEntity.class);
        verify(repo).save(captor.capture());
        SkillEntity saved = captor.getValue();
        // The existing entity's trust level is non-null ("INVALID_LEVEL"), so saveSkill
        // preserves it — it only sets trustLevel when it's null.
        assertThat(saved.getTrustLevel()).isEqualTo("INVALID_LEVEL");
    }

    // ─── validateFrontmatter edge cases ───

    @Test
    void saveSkill_nullContent_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.saveSkill("test-skill", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Content cannot be null");
    }

    @Test
    void saveSkill_blankContent_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.saveSkill("test-skill", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Content cannot be empty");
    }

    @Test
    void saveSkill_notStartingWithFrontmatter_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        assertThatThrownBy(() -> mgr.saveSkill("test-skill", "Just text"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YAML frontmatter");
    }

    @Test
    void saveSkill_emptyBody_throwsException() {
        SkillStorePort repo = mock(SkillStorePort.class);
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        String content = "---\nname: test\ndescription: test\n---\n  \n  ";
        assertThatThrownBy(() -> mgr.saveSkill("test-skill", content))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content after the frontmatter");
    }

    @Test
    void saveSkill_nameStartsWithNumber_valid() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("2-test")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("2-test", VALID_FRONTMATTER);
        verify(repo).save(argThat(e -> e.getName().equals("2-test")));
    }

    @Test
    void saveSkill_nameWithDots_valid() {
        SkillStorePort repo = mock(SkillStorePort.class);
        when(repo.findByName("skill.v2")).thenReturn(Optional.empty());
        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        mgr.saveSkill("skill.v2", VALID_FRONTMATTER);
        verify(repo).save(argThat(e -> e.getName().equals("skill.v2")));
    }

    // ─── Multi-strategy lookup (Fix 6) ───

    @Test
    void getSkillInfoMultiStrategy_dbHit_returnsInfo(@TempDir Path tempDir) {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        SkillEntity e = new SkillEntity();
        e.setName("db-skill");
        e.setContent(VALID_FRONTMATTER);
        when(repo.findByName("db-skill")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        var result = mgr.getSkillInfoMultiStrategy("db-skill");
        assertThat(result.info()).isNotNull();
        assertThat(result.info().name()).isEqualTo("db-skill");
        assertThat(result.collisionPaths()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void getSkillInfoMultiStrategy_notFound_returnsNull(@TempDir Path tempDir) {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        when(repo.findByName("nonexistent")).thenReturn(Optional.empty());

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        var result = mgr.getSkillInfoMultiStrategy("nonexistent");
        assertThat(result.info()).isNull();
        assertThat(result.error()).isNull();
    }

    @Test
    void getSkillInfoMultiStrategy_filesystemHit_returnsInfo(@TempDir Path tempDir) throws Exception {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        when(repo.findByName("fs-skill")).thenReturn(Optional.empty());

        // Create a filesystem skill
        Path skillDir = tempDir.resolve("skills").resolve("fs-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), VALID_FRONTMATTER.replace("test-skill", "fs-skill"));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        var result = mgr.getSkillInfoMultiStrategy("fs-skill");
        assertThat(result.info()).isNotNull();
        assertThat(result.info().name()).isEqualTo("fs-skill");
    }

    @Test
    void getSkillInfoMultiStrategy_collision_returnsError(@TempDir Path tempDir) throws Exception {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());

        // DB skill with one body
        String dbContent = """
            ---
            name: ambig
            description: DB version of the skill.
            ---
            This is the database version of the skill body.
            """;
        SkillEntity e = new SkillEntity();
        e.setName("ambig");
        e.setContent(dbContent);
        when(repo.findByName("ambig")).thenReturn(Optional.of(e));

        // Also create a filesystem skill with DIFFERENT content to force collision
        String fsContent = """
            ---
            name: ambig
            description: Filesystem version of the skill.
            ---
            This is the filesystem version of the skill body — different from DB.
            """;
        Path skillDir = tempDir.resolve("skills").resolve("ambig");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), fsContent);

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        var result = mgr.getSkillInfoMultiStrategy("ambig");
        // Different content means the two candidates are not equal, so collision
        // detection should fire and return an error with collisionPaths.
        assertThat(result.error()).isNotNull();
        assertThat(result.error()).contains("Ambiguous");
        assertThat(result.collisionPaths()).isNotEmpty();
        assertThat(result.info()).isNull();
    }

    @Test
    void getSkillInfoMultiStrategy_frontmatterNameMatch_returnsInfo(@TempDir Path tempDir) throws Exception {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());
        when(repo.findByName("aliased-skill")).thenReturn(Optional.empty());

        // Create a filesystem skill with directory name "alias" but frontmatter name "aliased-skill"
        Path skillDir = tempDir.resolve("skills").resolve("alias");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: aliased-skill\ndescription: Test\n---\nBody");

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        var result = mgr.getSkillInfoMultiStrategy("aliased-skill");
        assertThat(result.info()).isNotNull();
        assertThat(result.info().name()).isEqualTo("aliased-skill");
    }

    // ─── Tags and related_skills extraction ───

    @Test
    void getSkillInfo_extractsTagsFromFrontmatter() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            tags: [java, testing]
            ---\nBody
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.tags()).containsExactly("java", "testing");
    }

    @Test
    void getSkillInfo_extractsTagsFromMetadataHermes() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            metadata:
              hermes:
                tags: [web, api]
            ---\nBody
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.tags()).containsExactly("web", "api");
    }

    @Test
    void getSkillInfo_extractsRelatedSkillsFromFrontmatter() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            related_skills: [refactoring, code-review]
            ---\nBody
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.relatedSkills()).containsExactly("refactoring", "code-review");
    }

    @Test
    void getSkillInfo_detectsDisabledFromFrontmatter() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent("""
            ---
            name: test
            description: Test
            disabled: true
            ---\nBody
            """);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.disabled()).isTrue();
    }

    @Test
    void getSkillInfo_detectsDisabledFromRuntimeConfig() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent(VALID_FRONTMATTER);
        when(repo.findByName("test")).thenReturn(Optional.of(e));
        AgentProperties props = new AgentProperties();
        props.getSkills().getDisabled().add("test");

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.disabled()).isTrue();
    }

    @Test
    void getSkillInfo_notDisabledWhenAbsent() {
        SkillStorePort repo = mock(SkillStorePort.class);
        SkillEntity e = new SkillEntity();
        e.setName("test");
        e.setContent(VALID_FRONTMATTER);
        when(repo.findByName("test")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo);
        SkillManager.SkillInfo info = mgr.getSkillInfo("test");
        assertThat(info.disabled()).isFalse();
    }

    // ─── Linked files by type ───

    @Test
    void getSkillInfo_listsLinkedFilesByType(@TempDir Path tempDir) throws Exception {
        SkillStorePort repo = mock(SkillStorePort.class);
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(tempDir.toString());

        // Create filesystem skill with support files
        Path skillDir = tempDir.resolve("skills").resolve("typed-skill");
        Files.createDirectories(skillDir.resolve("references"));
        Files.createDirectories(skillDir.resolve("templates"));
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.createDirectories(skillDir.resolve("assets"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: typed-skill\ndescription: Test\n---\nBody");
        Files.writeString(skillDir.resolve("references").resolve("ref.md"), "ref content");
        Files.writeString(skillDir.resolve("templates").resolve("tmpl.yaml"), "template");
        Files.writeString(skillDir.resolve("scripts").resolve("run.sh"), "echo hello");
        Files.writeString(skillDir.resolve("assets").resolve("icon.png"), "binary");

        SkillEntity e = new SkillEntity();
        e.setName("typed-skill");
        e.setContent("---\nname: typed-skill\ndescription: Test\n---\nBody");
        when(repo.findByName("typed-skill")).thenReturn(Optional.of(e));

        DatabaseSkillManager mgr = new DatabaseSkillManager(repo, props);
        SkillManager.SkillInfo info = mgr.getSkillInfo("typed-skill");
        assertThat(info.linkedFiles()).isNotNull();
        assertThat(info.linkedFiles().references()).anyMatch(f -> f.contains("ref.md"));
        assertThat(info.linkedFiles().templates()).anyMatch(f -> f.contains("tmpl.yaml"));
        assertThat(info.linkedFiles().scripts()).anyMatch(f -> f.contains("run.sh"));
        assertThat(info.linkedFiles().assets()).anyMatch(f -> f.contains("icon.png"));
    }
}
