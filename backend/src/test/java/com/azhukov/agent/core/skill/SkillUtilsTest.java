package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S6: Tests for SkillUtils — frontmatter parsing, platform/env matching,
 * external dirs, excluded dirs, disabled skills, skill config.
 */
class SkillUtilsTest {

    @TempDir
    Path tempDir;

    private AgentProperties createProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        return properties;
    }

    private SkillUtils createSkillUtils() {
        return new SkillUtils(createProperties());
    }

    // ── Frontmatter parsing ──────────────────────────────────────────────

    @Test
    void parseFrontmatter_emptyContent() {
        var result = SkillUtils.parseFrontmatter("");
        assertThat(result.frontmatter()).isEmpty();
        assertThat(result.body()).isEqualTo("");
    }

    @Test
    void parseFrontmatter_noFrontmatter() {
        var result = SkillUtils.parseFrontmatter("# Title\nSome content");
        assertThat(result.frontmatter()).isEmpty();
        assertThat(result.body()).isEqualTo("# Title\nSome content");
    }

    @Test
    void parseFrontmatter_simpleYaml() {
        String content = """
            ---
            name: my-skill
            description: A test skill
            ---
            # My Skill
            Body content
            """;
        var result = SkillUtils.parseFrontmatter(content);
        assertThat(result.frontmatter()).containsEntry("name", "my-skill");
        assertThat(result.frontmatter()).containsEntry("description", "A test skill");
        assertThat(result.body()).startsWith("# My Skill");
    }

    @Test
    void parseFrontmatter_nestedMetadata() {
        String content = """
            ---
            name: test
            metadata:
              hermes:
                config:
                  - key: wiki.path
                    description: Wiki path
            ---
            Body
            """;
        var result = SkillUtils.parseFrontmatter(content);
        assertThat(result.frontmatter()).containsKey("metadata");
    }

    @Test
    void parseFrontmatter_malformedYaml_fallsBackToSimpleParsing() {
        String content = """
            ---
            name: test
            description: simple
            ---
            Body
            """;
        var result = SkillUtils.parseFrontmatter(content);
        assertThat(result.frontmatter()).containsEntry("name", "test");
    }

    // ── Platform matching ────────────────────────────────────────────────

    @Test
    void skillMatchesPlatform_noPlatformsField() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of())).isTrue();
    }

    @Test
    void skillMatchesPlatform_emptyPlatforms() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of("platforms", List.of()))).isTrue();
    }

    @Test
    void skillMatchesPlatform_singlePlatform() {
        // Linux should match linux
        assertThat(SkillUtils.skillMatchesPlatform(Map.of("platforms", List.of("linux")))).isTrue();
    }

    @Test
    void skillMatchesPlatform_multiplePlatforms() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of("platforms", List.of("macos", "linux")))).isTrue();
    }

    // ── Environment matching ─────────────────────────────────────────────

    @Test
    void skillMatchesEnvironment_noEnvironmentsField() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of())).isTrue();
    }

    @Test
    void skillMatchesEnvironment_emptyEnvironments() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of("environments", List.of()))).isTrue();
    }

    @Test
    void skillMatchesEnvironment_unknownTagFailsOpen() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of("environments", List.of("unknown-env")))).isTrue();
    }

    // ── Excluded dirs ────────────────────────────────────────────────────

    @Test
    void isExcludedSkillPath_gitDirectory() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/skills/.git/my-skill/SKILL.md"))).isTrue();
    }

    @Test
    void isExcludedSkillPath_nodeModules() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/skills/node_modules/skill/SKILL.md"))).isTrue();
    }

    @Test
    void isExcludedSkillPath_normalPath() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/skills/my-skill/SKILL.md"))).isFalse();
    }

    @Test
    void getExcludedSkillDirs_containsExpectedDirs() {
        var dirs = SkillUtils.getExcludedSkillDirs();
        assertThat(dirs).contains(".git", "node_modules", "venv", "__pycache__");
    }

    // ── Slugify ──────────────────────────────────────────────────────────

    @Test
    void slugify_normalizedName() {
        assertThat(SkillUtils.slugify("My Cool Skill")).isEqualTo("my-cool-skill");
    }

    @Test
    void slugify_replacesUnderscores() {
        assertThat(SkillUtils.slugify("my_cool_skill")).isEqualTo("my-cool-skill");
    }

    @Test
    void slugify_stripsSpecialChars() {
        assertThat(SkillUtils.slugify("Skill+Name")).isEqualTo("skillname");
    }

    @Test
    void slugify_collapsesMultipleHyphens() {
        assertThat(SkillUtils.slugify("a   b")).isEqualTo("a-b");
    }

    @Test
    void slugify_emptyName() {
        assertThat(SkillUtils.slugify("")).isEmpty();
    }

    @Test
    void slugify_nullName() {
        assertThat(SkillUtils.slugify(null)).isEmpty();
    }

    // ── Disabled skills ──────────────────────────────────────────────────

    @Test
    void getDisabledSkillNames_emptyByDefault() {
        SkillUtils utils = createSkillUtils();
        assertThat(utils.getDisabledSkillNames()).isEmpty();
    }

    @Test
    void getDisabledSkillNames_readsFromConfig() {
        AgentProperties props = createProperties();
        props.getSkills().setDisabled(List.of("skill1", "skill2"));
        SkillUtils utils = new SkillUtils(props);
        assertThat(utils.getDisabledSkillNames()).containsExactly("skill1", "skill2");
    }

    @Test
    void getDisabledSkillNames_platformSpecific() {
        AgentProperties props = createProperties();
        props.getSkills().getPlatformDisabled().put("telegram", List.of("telegram-only-skill"));
        SkillUtils utils = new SkillUtils(props);
        assertThat(utils.getDisabledSkillNames("telegram")).containsExactly("telegram-only-skill");
    }

    @Test
    void getDisabledSkillNames_fallbackToGlobalWhenPlatformNotFound() {
        AgentProperties props = createProperties();
        props.getSkills().setDisabled(List.of("global-disabled"));
        SkillUtils utils = new SkillUtils(props);
        assertThat(utils.getDisabledSkillNames("unknown-platform")).containsExactly("global-disabled");
    }

    // ── External dirs ────────────────────────────────────────────────────

    @Test
    void getExternalSkillsDirs_emptyByDefault() {
        SkillUtils utils = createSkillUtils();
        assertThat(utils.getExternalSkillsDirs()).isEmpty();
    }

    @Test
    void getExternalSkillsDirs_readsFromConfig() throws IOException {
        Path extDir = tempDir.resolve("external-skills");
        Files.createDirectories(extDir);

        AgentProperties props = createProperties();
        props.getSkills().setExternalDirs(List.of(extDir.toString()));
        SkillUtils utils = new SkillUtils(props);

        assertThat(utils.getExternalSkillsDirs()).contains(extDir.toAbsolutePath().normalize());
    }

    @Test
    void getExternalSkillsDirs_skipsNonExistentDirs() {
        AgentProperties props = createProperties();
        props.getSkills().setExternalDirs(List.of("/nonexistent/path/xyz"));
        SkillUtils utils = new SkillUtils(props);
        assertThat(utils.getExternalSkillsDirs()).isEmpty();
    }

    @Test
    void getExternalSkillsDirs_skipsLocalSkillsDir() {
        AgentProperties props = createProperties();
        Path localSkills = tempDir.resolve("skills");
        props.getSkills().setExternalDirs(List.of(localSkills.toString()));
        SkillUtils utils = new SkillUtils(props);
        assertThat(utils.getExternalSkillsDirs()).isEmpty();
    }

    @Test
    void getExternalSkillsDirs_expandsTilde() throws IOException {
        Path homeDir = Path.of(System.getProperty("user.home"));
        Path extDir = homeDir.resolve("test-ext-skills-dir-xyz");
        Files.createDirectories(extDir);
        try {
            AgentProperties props = createProperties();
            props.getSkills().setExternalDirs(List.of("~/test-ext-skills-dir-xyz"));
            SkillUtils utils = new SkillUtils(props);
            assertThat(utils.getExternalSkillsDirs()).contains(extDir.toAbsolutePath().normalize());
        } finally {
            Files.deleteIfExists(extDir);
        }
    }

    @Test
    void getAllSkillsDirs_includesLocalAndExternal() throws IOException {
        Path extDir = tempDir.resolve("ext-skills");
        Files.createDirectories(extDir);

        AgentProperties props = createProperties();
        props.getSkills().setExternalDirs(List.of(extDir.toString()));
        SkillUtils utils = new SkillUtils(props);

        var dirs = utils.getAllSkillsDirs();
        assertThat(dirs).hasSize(2);
        assertThat(dirs.get(0)).isEqualTo(tempDir.resolve("skills")); // local first
        assertThat(dirs.get(1)).isEqualTo(extDir.toAbsolutePath().normalize());
    }

    // ── Skill file iteration ─────────────────────────────────────────────

    @Test
    void iterSkillIndexFiles_findsSkillMd() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path skill1 = skillsDir.resolve("skill1");
        Files.createDirectories(skill1);
        Files.writeString(skill1.resolve("SKILL.md"), "# Skill 1");

        var files = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString()).isEqualTo("SKILL.md");
    }

    @Test
    void iterSkillIndexFiles_excludesExcludedDirs() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path gitDir = skillsDir.resolve(".git").resolve("nested");
        Path normalSkill = skillsDir.resolve("my-skill");
        Files.createDirectories(gitDir);
        Files.createDirectories(normalSkill);
        Files.writeString(gitDir.resolve("SKILL.md"), "# Fake skill");
        Files.writeString(normalSkill.resolve("SKILL.md"), "# Real skill");

        var files = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getParent().getFileName().toString()).isEqualTo("my-skill");
    }

    // ── Skill config extraction ──────────────────────────────────────────

    @Test
    void extractSkillConfigVars_noMetadata() {
        assertThat(SkillUtils.extractSkillConfigVars(Map.of())).isEmpty();
    }

    @Test
    void extractSkillConfigVars_withValidConfig() {
        Map<String, Object> frontmatter = Map.of(
            "metadata", Map.of(
                "hermes", Map.of(
                    "config", List.of(
                        Map.of("key", "wiki.path", "description", "Wiki path", "default", "~/wiki"),
                        Map.of("key", "search.engine", "description", "Search engine")
                    )
                )
            )
        );
        var vars = SkillUtils.extractSkillConfigVars(frontmatter);
        assertThat(vars).hasSize(2);
        assertThat(vars.get(0).key()).isEqualTo("wiki.path");
        assertThat(vars.get(0).defaultValue()).isEqualTo("~/wiki");
        assertThat(vars.get(1).key()).isEqualTo("search.engine");
    }

    @Test
    void extractSkillConfigVars_skipsInvalidEntries() {
        Map<String, Object> frontmatter = Map.of(
            "metadata", Map.of(
                "hermes", Map.of(
                    "config", List.of(
                        Map.of("key", "", "description", "empty key"),
                        Map.of("key", "valid.key", "description", "valid"),
                        Map.of("description", "no key")
                    )
                )
            )
        );
        var vars = SkillUtils.extractSkillConfigVars(frontmatter);
        assertThat(vars).hasSize(1);
        assertThat(vars.get(0).key()).isEqualTo("valid.key");
    }

    // ── Description extraction ───────────────────────────────────────────

    @Test
    void extractSkillDescription_normalDescription() {
        assertThat(SkillUtils.extractSkillDescription(Map.of("description", "A test skill")))
            .isEqualTo("A test skill");
    }

    @Test
    void extractSkillDescription_truncatesLongDescriptions() {
        String longDesc = "a".repeat(100);
        String result = SkillUtils.extractSkillDescription(Map.of("description", longDesc));
        assertThat(result).hasSize(60);
        assertThat(result).endsWith("...");
    }

    @Test
    void extractSkillDescription_stripsQuotes() {
        assertThat(SkillUtils.extractSkillDescription(Map.of("description", "'quoted'")))
            .isEqualTo("quoted");
    }

    @Test
    void extractSkillDescription_emptyDescription() {
        assertThat(SkillUtils.extractSkillDescription(Map.of())).isEmpty();
    }

    // ── Discover all skill config vars ───────────────────────────────────

    @Test
    void discoverAllSkillConfigVars_scansAndDedupes() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path skill1 = skillsDir.resolve("skill1");
        Path skill2 = skillsDir.resolve("skill2");
        Files.createDirectories(skill1);
        Files.createDirectories(skill2);
        Files.writeString(skill1.resolve("SKILL.md"), """
            ---
            name: skill1
            metadata:
              hermes:
                config:
                  - key: shared.key
                    description: Shared config
                  - key: skill1.key
                    description: Skill1 specific
            ---
            Body
            """);
        Files.writeString(skill2.resolve("SKILL.md"), """
            ---
            name: skill2
            metadata:
              hermes:
                config:
                  - key: shared.key
                    description: Shared config (dup)
                  - key: skill2.key
                    description: Skill2 specific
            ---
            Body
            """);

        SkillUtils utils = createSkillUtils();
        var vars = utils.discoverAllSkillConfigVars();
        // shared.key should be deduped — only 3 entries, not 4
        assertThat(vars).hasSize(3);
        assertThat(vars.stream().map(v -> v.var().key()))
            .containsExactlyInAnyOrder("shared.key", "skill1.key", "skill2.key");
        // shared.key should be attributed to skill1 (first seen)
        var sharedVar = vars.stream().filter(v -> v.var().key().equals("shared.key")).findFirst().orElseThrow();
        assertThat(sharedVar.skill()).isEqualTo("skill1");
    }
}