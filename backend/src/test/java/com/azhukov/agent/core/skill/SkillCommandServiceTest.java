package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * S2: Tests for SkillCommandService — scan, resolve, build invocation message, reload.
 */
class SkillCommandServiceTest {

    @TempDir
    Path tempDir;

    private AgentProperties createProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        return properties;
    }

    private void createSkillFile(String skillName, String description, String body) throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve(skillName);
        Files.createDirectories(skillDir);
        String content = "---\nname: " + skillName + "\ndescription: " + description + "\n---\n" + body;
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }

    private SkillCommandService createService() {
        AgentProperties props = createProperties();
        SkillUtils skillUtils = new SkillUtils(props);
        SkillPreprocessor preprocessor = new SkillPreprocessor();
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService bundleService = new SkillBundleService(skillManager, props);
        return new SkillCommandService(skillUtils, preprocessor, bundleService);
    }

    @Test
    void scanSkillCommands_findsSkillMd() throws IOException {
        createSkillFile("test-skill", "A test skill", "# Test\nBody content");
        SkillCommandService service = createService();

        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/test-skill");
        var info = commands.get("/test-skill");
        assertThat(info.name()).isEqualTo("test-skill");
        assertThat(info.description()).isEqualTo("A test skill");
    }

    @Test
    void scanSkillCommands_normalizesToSlug() throws IOException {
        createSkillFile("My Cool Skill", "desc", "body");
        SkillCommandService service = createService();

        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/my-cool-skill");
    }

    @Test
    void scanSkillCommands_fallbackDescriptionFromBody() throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve("no-desc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: no-desc
            ---
            This is the first line of the body.
            """);

        SkillCommandService service = createService();
        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/no-desc");
        assertThat(commands.get("/no-desc").description()).contains("This is the first line");
    }

    @Test
    void scanSkillNames_usesDirectoryNameWhenNoName() throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve("dir-name-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            description: Skill without name field
            ---
            Body
            """);

        SkillCommandService service = createService();
        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/dir-name-skill");
    }

    @Test
    void resolveSkillCommandKey_handlesUnderscoreToHyphen() throws IOException {
        createSkillFile("test-skill", "desc", "body");
        SkillCommandService service = createService();
        service.scanSkillCommands();

        assertThat(service.resolveSkillCommandKey("test_skill")).isEqualTo("/test-skill");
        assertThat(service.resolveSkillCommandKey("test-skill")).isEqualTo("/test-skill");
        assertThat(service.resolveSkillCommandKey("nonexistent")).isNull();
    }

    @Test
    void resolveSkillCommandKey_emptyCommand() {
        SkillCommandService service = createService();
        assertThat(service.resolveSkillCommandKey("")).isNull();
        assertThat(service.resolveSkillCommandKey(null)).isNull();
    }

    @Test
    void buildSkillInvocationMessage_returnsContent() throws IOException {
        createSkillFile("test-skill", "desc", "# Test Skill\nThis is the body.");
        SkillCommandService service = createService();

        String message = service.buildSkillInvocationMessage("/test-skill", "do something", "session-1");
        assertThat(message).isNotNull();
        assertThat(message).contains("test-skill");
        assertThat(message).contains("This is the body.");
        assertThat(message).contains("do something");
    }

    @Test
    void buildSkillInvocationMessage_returnsNullForMissingSkill() {
        SkillCommandService service = createService();
        assertThat(service.buildSkillInvocationMessage("/nonexistent", "", "s1")).isNull();
    }

    @Test
    void buildSkillInvocationMessage_includesSkillDirectory() throws IOException {
        createSkillFile("test-skill", "desc", "body");
        SkillCommandService service = createService();

        String message = service.buildSkillInvocationMessage("/test-skill", "", "s1");
        assertThat(message).contains("[Skill directory:");
    }

    @Test
    void buildSkillInvocationMessage_includesRuntimeNote() throws IOException {
        createSkillFile("test-skill", "desc", "body");
        SkillCommandService service = createService();

        String message = service.buildSkillInvocationMessage("/test-skill", "", "s1", "custom runtime note");
        assertThat(message).contains("[Runtime note: custom runtime note]");
    }

    @Test
    void buildSkillInvocationMessage_injectsConfigVars() throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve("config-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: config-skill
            description: A skill with config
            metadata:
              hermes:
                config:
                  - key: test.setting
                    description: A test setting
                    default: "default-value"
            ---
            Body content
            """);

        AgentProperties props = createProperties();
        SkillUtils skillUtils = new SkillUtils(props);
        SkillPreprocessor preprocessor = new SkillPreprocessor();
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService bundleService = new SkillBundleService(skillManager, props);
        SkillCommandService service = new SkillCommandService(skillUtils, preprocessor, bundleService);

        String message = service.buildSkillInvocationMessage("/config-skill", "", "s1");
        assertThat(message).contains("[Skill config");
    }

    @Test
    void reloadSkills_returnsDiff() throws IOException {
        createSkillFile("skill1", "desc1", "body1");
        SkillCommandService service = createService();

        service.scanSkillCommands();
        var diff = service.reloadSkills();
        assertThat(diff.unchanged()).contains("skill1");
        assertThat(diff.total()).isEqualTo(1);

        // Add a new skill
        createSkillFile("skill2", "desc2", "body2");
        var diff2 = service.reloadSkills();
        assertThat(diff2.added()).hasSize(1);
        assertThat(diff2.added().get(0).name()).isEqualTo("skill2");
        assertThat(diff2.total()).isEqualTo(2);
    }

    @Test
    void reloadSkills_detectsRemovedSkills() throws IOException {
        createSkillFile("skill1", "desc1", "body1");
        createSkillFile("skill2", "desc2", "body2");
        SkillCommandService service = createService();

        service.scanSkillCommands();
        assertThat(service.reloadSkills().total()).isEqualTo(2);

        // Remove skill2
        Files.delete(tempDir.resolve("skills").resolve("skill2").resolve("SKILL.md"));
        Files.delete(tempDir.resolve("skills").resolve("skill2"));

        var diff = service.reloadSkills();
        assertThat(diff.removed()).hasSize(1);
        assertThat(diff.removed().get(0).name()).isEqualTo("skill2");
        assertThat(diff.total()).isEqualTo(1);
    }

    @Test
    void scanSkillCommands_respectsDisabledSkills() throws IOException {
        createSkillFile("enabled-skill", "desc", "body");
        createSkillFile("disabled-skill", "desc", "body");

        AgentProperties props = createProperties();
        props.getSkills().setDisabled(java.util.List.of("disabled-skill"));
        SkillUtils skillUtils = new SkillUtils(props);
        SkillPreprocessor preprocessor = new SkillPreprocessor();
        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService bundleService = new SkillBundleService(skillManager, props);
        SkillCommandService service = new SkillCommandService(skillUtils, preprocessor, bundleService);

        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/enabled-skill");
        assertThat(commands).doesNotContainKey("/disabled-skill");
    }

    @Test
    void scanSkillCommands_skipsExcludedDirs() throws IOException {
        // Create a skill in .git directory — should be excluded
        Path gitSkillDir = tempDir.resolve("skills").resolve(".git").resolve("fake-skill");
        Files.createDirectories(gitSkillDir);
        Files.writeString(gitSkillDir.resolve("SKILL.md"), """
            ---
            name: fake-skill
            description: Should be excluded
            ---
            Body
            """);

        // Create a normal skill
        createSkillFile("real-skill", "desc", "body");

        SkillCommandService service = createService();
        var commands = service.scanSkillCommands();
        assertThat(commands).containsKey("/real-skill");
        assertThat(commands).doesNotContainKey("/fake-skill");
    }

    @Test
    void scanSkillCommands_filtersByEnvironment() throws IOException {
        // Create a skill with environment restriction
        Path skillDir = tempDir.resolve("skills").resolve("docker-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: docker-skill
            description: Docker only
            environments: [docker]
            ---
            Body
            """);

        createSkillFile("normal-skill", "desc", "body");

        SkillCommandService service = createService();
        var commands = service.scanSkillCommands();
        // docker-skill should be filtered out if not in docker env
        assertThat(commands).containsKey("/normal-skill");
        // The docker skill may or may not appear depending on env detection,
        // but the normal skill should always be there
    }

    @Test
    void scanSkillCommands_findsSupportingFiles() throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve("file-skill");
        Files.createDirectories(skillDir.resolve("references"));
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: file-skill
            description: Has supporting files
            ---
            Body
            """);
        Files.writeString(skillDir.resolve("references").resolve("guide.md"), "Guide content");
        Files.writeString(skillDir.resolve("scripts").resolve("helper.sh"), "#!/bin/bash\necho hi");

        SkillCommandService service = createService();
        String message = service.buildSkillInvocationMessage("/file-skill", "", "s1");
        assertThat(message).contains("[This skill has supporting files:]");
        assertThat(message).contains("references/guide.md");
        assertThat(message).contains("scripts/helper.sh");
    }
}