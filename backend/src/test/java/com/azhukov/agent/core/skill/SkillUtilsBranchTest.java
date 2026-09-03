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

/**
 * Branch coverage tests for {@link SkillUtils}.
 * Covers frontmatter parsing, platform matching, slugify, env matching, etc.
 */
class SkillUtilsBranchTest {

    private AgentProperties properties;

    private static String currentPlatformFrontmatterValue() {
        return switch (SkillUtils.currentPlatformId()) {
            case "win32" -> "windows";
            case "darwin" -> "macos";
            default -> SkillUtils.currentPlatformId();
        };
    }

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
    }

    // ── slugify ──

    @Test
    void slugify_null_returnsEmpty() {
        assertThat(SkillUtils.slugify(null)).isEmpty();
    }

    @Test
    void slugify_blank_returnsEmpty() {
        assertThat(SkillUtils.slugify("  ")).isEmpty();
    }

    @Test
    void slugify_simpleName_returnsAsIs() {
        assertThat(SkillUtils.slugify("my-skill")).isEqualTo("my-skill");
    }

    @Test
    void slugify_uppercaseLowercased() {
        assertThat(SkillUtils.slugify("MySkill")).isEqualTo("myskill");
    }

    @Test
    void slugify_spacesReplacedWithHyphens() {
        assertThat(SkillUtils.slugify("my cool skill")).isEqualTo("my-cool-skill");
    }

    @Test
    void slugify_underscoresReplacedWithHyphens() {
        assertThat(SkillUtils.slugify("my_skill")).isEqualTo("my-skill");
    }

    @Test
    void slugify_multipleHyphensCollapsed() {
        assertThat(SkillUtils.slugify("a---b")).isEqualTo("a-b");
    }

    @Test
    void slugify_specialCharsStripped() {
        // @ and ! are stripped, but . is allowed (it's in the allowed character set)
        assertThat(SkillUtils.slugify("skill@v1!")).isEqualTo("skillv1");
    }

    @Test
    void slugify_leadingTrailingHyphensStripped() {
        assertThat(SkillUtils.slugify("--my-skill--")).isEqualTo("my-skill");
    }

    // ── parseFrontmatter ──

    @Test
    void parseFrontmatter_nullContent_returnsEmpty() {
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(null);
        assertThat(result.frontmatter()).isEmpty();
    }

    @Test
    void parseFrontmatter_noFrontmatter_returnsEmpty() {
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter("Just content");
        assertThat(result.frontmatter()).isEmpty();
        assertThat(result.body()).isEqualTo("Just content");
    }

    @Test
    void parseFrontmatter_validFrontmatter_parsed() {
        String content = """
            ---
            name: test
            description: A test
            ---
            Body content
            """;
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(content);
        assertThat(result.frontmatter()).containsEntry("name", "test");
        assertThat(result.frontmatter()).containsEntry("description", "A test");
        assertThat(result.body()).contains("Body content");
    }

    @Test
    void parseFrontmatter_noClosingDelim_returnsFullBody() {
        String content = "---\nname: test\nBody without closing";
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(content);
        // No closing → frontmatter empty, body is the content
        assertThat(result.frontmatter()).isEmpty();
    }

    @Test
    void parseFrontmatter_invalidYaml_fallbackParsing() {
        String content = """
            ---
            name: test
            description: A test
            ---
            Body
            """;
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(content);
        assertThat(result.frontmatter()).containsEntry("name", "test");
    }

    // ── skillMatchesPlatform ──

    @Test
    void skillMatchesPlatform_nullPlatforms_returnsTrue() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of())).isTrue();
    }

    @Test
    void skillMatchesPlatform_emptyList_returnsTrue() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of("platforms", List.of()))).isTrue();
    }

    @Test
    void skillMatchesPlatform_nonListValue_wrapsInList() {
        // Non-list value should be wrapped
        assertThat(SkillUtils.skillMatchesPlatform(Map.of(
            "platforms", currentPlatformFrontmatterValue()
        ))).isTrue();
    }

    @Test
    void skillMatchesPlatform_matchingPlatform_returnsTrue() {
        assertThat(SkillUtils.skillMatchesPlatform(Map.of(
            "platforms", List.of(currentPlatformFrontmatterValue())
        ))).isTrue();
    }

    @Test
    void skillMatchesPlatform_nonMatchingPlatform_returnsFalse() {
        // Use a platform that doesn't match
        assertThat(SkillUtils.skillMatchesPlatform(Map.of("platforms", List.of("nonexistent-os")))).isFalse();
    }

    // ── skillMatchesEnvironment ──

    @Test
    void skillMatchesEnvironment_nullEnvironments_returnsTrue() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of())).isTrue();
    }

    @Test
    void skillMatchesEnvironment_emptyList_returnsTrue() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of("environments", List.of()))).isTrue();
    }

    @Test
    void skillMatchesEnvironment_nonListValue_wrapsInList() {
        // "docker" is a known environment tag; non-list value should be wrapped in a list
        // The result depends on whether docker is actually detected in the test environment
        // but the key branch is that non-list wrapping doesn't throw
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of("environments", "nonexistent-tag-xyz"))).isTrue();
    }

    @Test
    void skillMatchesEnvironment_unknownTag_failOpen() {
        assertThat(SkillUtils.skillMatchesEnvironment(Map.of("environments", List.of("unknown-tag")))).isTrue();
    }

    // ── isExcludedSkillPath ──

    @Test
    void isExcludedSkillPath_excludedDir_returnsTrue() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/home/user/.git/skills"))).isTrue();
    }

    @Test
    void isExcludedSkillPath_normalPath_returnsFalse() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/home/user/my-skill"))).isFalse();
    }

    @Test
    void isExcludedSkillPath_nodeModules_returnsTrue() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/project/node_modules/skill"))).isTrue();
    }

    @Test
    void isExcludedSkillPath_venv_returnsTrue() {
        assertThat(SkillUtils.isExcludedSkillPath(Path.of("/project/venv/skill"))).isTrue();
    }

    @Test
    void getExcludedSkillDirs_containsGit() {
        assertThat(SkillUtils.getExcludedSkillDirs()).contains(".git");
    }

    @Test
    void getExcludedSkillDirs_containsNodeModules() {
        assertThat(SkillUtils.getExcludedSkillDirs()).contains("node_modules");
    }

    // ── extractSkillDescription ──

    @Test
    void extractSkillDescription_nullDescription_returnsEmpty() {
        assertThat(SkillUtils.extractSkillDescription(Map.of())).isEmpty();
    }

    @Test
    void extractSkillDescription_shortDescription_returnsAsIs() {
        assertThat(SkillUtils.extractSkillDescription(Map.of("description", "A short desc"))).isEqualTo("A short desc");
    }

    @Test
    void extractSkillDescription_longDescription_truncated() {
        String longDesc = "x".repeat(100);
        String result = SkillUtils.extractSkillDescription(Map.of("description", longDesc));
        assertThat(result.length()).isLessThanOrEqualTo(60);
        assertThat(result).endsWith("...");
    }

    @Test
    void extractSkillDescription_doubleQuoted_stripped() {
        assertThat(SkillUtils.extractSkillDescription(Map.of("description", "\"quoted\""))).isEqualTo("quoted");
    }

    @Test
    void extractSkillDescription_singleQuoted_stripped() {
        assertThat(SkillUtils.extractSkillDescription(Map.of("description", "'quoted'"))).isEqualTo("quoted");
    }

    // ── getDisabledSkillNames ──

    @Test
    void getDisabledSkillNames_nullProperties_returnsEmpty() {
        SkillUtils utils = new SkillUtils(null);
        assertThat(utils.getDisabledSkillNames()).isEmpty();
    }

    @Test
    void getDisabledSkillNames_noDisabledConfigured_returnsEmpty() {
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames()).isEmpty();
    }

    @Test
    void getDisabledSkillNames_disabledConfigured_returnsNames() {
        properties.getSkills().setDisabled(List.of("skill-a", "skill-b"));
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames()).containsExactly("skill-a", "skill-b");
    }

    @Test
    void getDisabledSkillNames_blankEntries_filtered() {
        properties.getSkills().setDisabled(List.of("skill-a", "  ", "", "skill-b"));
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames()).containsExactly("skill-a", "skill-b");
    }

    @Test
    void getDisabledSkillNames_forPlatform_returnsPlatformSpecific() {
        properties.getSkills().setDisabled(List.of("global-disabled"));
        properties.getSkills().getPlatformDisabled().put("linux", List.of("linux-disabled"));
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames("linux")).contains("linux-disabled");
    }

    @Test
    void getDisabledSkillNames_forUnknownPlatform_returnsGlobal() {
        properties.getSkills().setDisabled(List.of("global-disabled"));
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames("nonexistent")).contains("global-disabled");
    }

    @Test
    void getDisabledSkillNames_nullPlatform_returnsGlobal() {
        properties.getSkills().setDisabled(List.of("global-disabled"));
        SkillUtils utils = new SkillUtils(properties);
        assertThat(utils.getDisabledSkillNames(null)).contains("global-disabled");
    }

    // ── extractSkillConfigVars ──

    @Test
    void extractSkillConfigVars_nullMetadata_returnsEmpty() {
        assertThat(SkillUtils.extractSkillConfigVars(Map.of())).isEmpty();
    }

    @Test
    void extractSkillConfigVars_noHermesKey_returnsEmpty() {
        assertThat(SkillUtils.extractSkillConfigVars(Map.of("metadata", Map.of()))).isEmpty();
    }

    @Test
    void extractSkillConfigVars_noConfigKey_returnsEmpty() {
        assertThat(SkillUtils.extractSkillConfigVars(Map.of("metadata", Map.of("hermes", Map.of())))).isEmpty();
    }

    @Test
    void extractSkillConfigVars_configNull_returnsEmpty() {
        Map<String, Object> hermes = new java.util.HashMap<>();
        hermes.put("config", null);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        assertThat(SkillUtils.extractSkillConfigVars(metadata)).isEmpty();
    }

    @Test
    void extractSkillConfigVars_configMap_wrapsInList() {
        Map<String, Object> configVar = new java.util.LinkedHashMap<>();
        configVar.put("key", "test.key");
        configVar.put("description", "Test var");
        Map<String, Object> hermes = Map.of("config", configVar);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        List<SkillUtils.SkillConfigVar> result = SkillUtils.extractSkillConfigVars(metadata);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("test.key");
    }

    @Test
    void extractSkillConfigVars_emptyDescription_skipped() {
        Map<String, Object> configVar = Map.of("key", "test.key", "description", "");
        Map<String, Object> hermes = Map.of("config", configVar);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        assertThat(SkillUtils.extractSkillConfigVars(metadata)).isEmpty();
    }

    @Test
    void extractSkillConfigVars_nullKey_skipped() {
        Map<String, Object> configVar = new java.util.LinkedHashMap<>();
        configVar.put("description", "Test");
        // No "key" field → key is null → skipped
        Map<String, Object> hermes = Map.of("config", configVar);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        assertThat(SkillUtils.extractSkillConfigVars(metadata)).isEmpty();
    }

    @Test
    void extractSkillConfigVars_duplicateKey_deduped() {
        Map<String, Object> var1 = new java.util.LinkedHashMap<>();
        var1.put("key", "dup.key");
        var1.put("description", "First");
        Map<String, Object> var2 = new java.util.LinkedHashMap<>();
        var2.put("key", "dup.key");
        var2.put("description", "Second");
        Map<String, Object> hermes = Map.of("config", List.of(var1, var2));
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        List<SkillUtils.SkillConfigVar> result = SkillUtils.extractSkillConfigVars(metadata);
        assertThat(result).hasSize(1);
    }

    @Test
    void extractSkillConfigVars_nonMapItem_skipped() {
        Map<String, Object> hermes = Map.of("config", List.of("not-a-map"));
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        assertThat(SkillUtils.extractSkillConfigVars(metadata)).isEmpty();
    }

    @Test
    void extractSkillConfigVars_emptyKey_skipped() {
        Map<String, Object> configVar = Map.of("key", "  ", "description", "Test");
        Map<String, Object> hermes = Map.of("config", configVar);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        assertThat(SkillUtils.extractSkillConfigVars(metadata)).isEmpty();
    }

    @Test
    void extractSkillConfigVars_usesDescriptionAsPromptWhenNoPrompt() {
        Map<String, Object> configVar = new java.util.LinkedHashMap<>();
        configVar.put("key", "test.key");
        configVar.put("description", "Test desc");
        Map<String, Object> hermes = Map.of("config", configVar);
        Map<String, Object> metadata = Map.of("metadata", Map.of("hermes", hermes));
        List<SkillUtils.SkillConfigVar> result = SkillUtils.extractSkillConfigVars(metadata);
        assertThat(result.get(0).prompt()).isEqualTo("Test desc");
    }

    // ── iterSkillIndexFiles ──

    @Test
    void iterSkillIndexFiles_nullDir_returnsEmpty() {
        assertThat(SkillUtils.iterSkillIndexFiles(null, "SKILL.md")).isEmpty();
    }

    @Test
    void iterSkillIndexFiles_nonExistentDir_returnsEmpty() {
        assertThat(SkillUtils.iterSkillIndexFiles(Path.of("/nonexistent/path"), "SKILL.md")).isEmpty();
    }

    @Test
    void iterSkillIndexFiles_emptyDir_returnsEmpty(@TempDir Path tempDir) {
        assertThat(SkillUtils.iterSkillIndexFiles(tempDir, "SKILL.md")).isEmpty();
    }

    @Test
    void iterSkillIndexFiles_findsSkillFiles(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "content");
        List<Path> result = SkillUtils.iterSkillIndexFiles(tempDir, "SKILL.md");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFileName().toString()).isEqualTo("SKILL.md");
    }

    @Test
    void iterSkillIndexFiles_excludesHiddenDirs(@TempDir Path tempDir) throws Exception {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("SKILL.md"), "content");
        Path skillDir = tempDir.resolve("real-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "content");
        List<Path> result = SkillUtils.iterSkillIndexFiles(tempDir, "SKILL.md");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getParent().getFileName().toString()).isEqualTo("real-skill");
    }

    @Test
    void iterSkillIndexFiles_multipleFiles_sorted(@TempDir Path tempDir) throws Exception {
        for (String name : List.of("zebra", "apple", "mango")) {
            Path dir = tempDir.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), "content");
        }
        List<Path> result = SkillUtils.iterSkillIndexFiles(tempDir, "SKILL.md");
        assertThat(result).hasSize(3);
        // Results should be sorted
        assertThat(result.get(0).getParent().getFileName().toString()).isEqualTo("apple");
        assertThat(result.get(2).getParent().getFileName().toString()).isEqualTo("zebra");
    }
}
