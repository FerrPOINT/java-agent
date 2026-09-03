package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2: Tests for SkillPreprocessor — template var names, CWD bug fix, inline shell.
 */
class SkillPreprocessorTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String cwdCommand() {
        return isWindows() ? "cd" : "pwd";
    }

    private static String readFileCommand(String fileName) {
        return isWindows() ? "type " + fileName : "cat " + fileName;
    }

    @Test
    void substituteHermesSessionId() {
        SkillPreprocessor p = new SkillPreprocessor();
        String result = p.substituteTemplateVars("Session: ${HERMES_SESSION_ID}", "abc-123", null);
        assertThat(result).isEqualTo("Session: abc-123");
    }

    @Test
    void substituteHermesSkillDir() {
        SkillPreprocessor p = new SkillPreprocessor();
        String result = p.substituteTemplateVars("Dir: ${HERMES_SKILL_DIR}", null, "/skills/my-skill");
        assertThat(result).isEqualTo("Dir: /skills/my-skill");
    }

    @Test
    void backwardCompatSessionId() {
        SkillPreprocessor p = new SkillPreprocessor();
        // Old ${SESSION_ID} should still work
        String result = p.substituteTemplateVars("Session: ${SESSION_ID}", "xyz", null);
        assertThat(result).isEqualTo("Session: xyz");
    }

    @Test
    void backwardCompatSkillDir() {
        SkillPreprocessor p = new SkillPreprocessor();
        // Old ${SKILL_DIR} should still work
        String result = p.substituteTemplateVars("Dir: ${SKILL_DIR}", null, "/opt/skills/test");
        assertThat(result).isEqualTo("Dir: /opt/skills/test");
    }

    @Test
    void unresolvedTokenLeftAsIs() {
        SkillPreprocessor p = new SkillPreprocessor();
        String result = p.substituteTemplateVars("${HERMES_SESSION_ID} and ${HERMES_SKILL_DIR}", null, null);
        assertThat(result).isEqualTo("${HERMES_SESSION_ID} and ${HERMES_SKILL_DIR}");
    }

    @Test
    void multipleVarsInContent() {
        SkillPreprocessor p = new SkillPreprocessor();
        String content = "session=${HERMES_SESSION_ID} dir=${HERMES_SKILL_DIR} old=${SESSION_ID}";
        String result = p.substituteTemplateVars(content, "s1", "/d");
        assertThat(result).isEqualTo("session=s1 dir=/d old=s1");
    }

    @Test
    void preprocess_disabledReturnsOriginal() {
        SkillPreprocessor p = new SkillPreprocessor();
        p.setEnabled(false);
        String result = p.preprocess("${HERMES_SESSION_ID}", "x", null);
        assertThat(result).isEqualTo("${HERMES_SESSION_ID}");
    }

    @Test
    void preprocess_nullContentReturnsNull() {
        SkillPreprocessor p = new SkillPreprocessor();
        assertThat(p.preprocess(null, "x", "/d")).isNull();
    }

    @Test
    void preprocess_emptyContentReturnsEmpty() {
        SkillPreprocessor p = new SkillPreprocessor();
        assertThat(p.preprocess("", "x", "/d")).isEmpty();
    }

    @Test
    void inlineShell_notExpandedByDefault() {
        SkillPreprocessor p = new SkillPreprocessor();
        // Inline shell is disabled by default
        String content = "Today is !`echo hello`";
        String result = p.preprocess(content, null, "/tmp");
        assertThat(result).contains("!`echo hello`"); // not expanded
    }

    @Test
    void inlineShell_expandedWhenEnabled(@TempDir Path tempDir) {
        SkillPreprocessor p = new SkillPreprocessor();
        p.setInlineShellEnabled(true);
        String content = "Result: !`echo test`";
        String result = p.preprocess(content, null, tempDir.toString());
        assertThat(result).isEqualTo("Result: test");
    }

    @Test
    void inlineShell_setsCwdToSkillDir(@TempDir Path tempDir) throws IOException {
        SkillPreprocessor p = new SkillPreprocessor();
        p.setInlineShellEnabled(true);
        // Running pwd should return the skill directory
        String content = "CWD: !`" + cwdCommand() + "`";
        String result = p.preprocess(content, null, tempDir.toString());
        String cwd = result.substring("CWD: ".length()).trim();
        assertThat(Files.isSameFile(Path.of(cwd), tempDir)).isTrue();
    }

    @Test
    void inlineShell_handlesError() {
        SkillPreprocessor p = new SkillPreprocessor();
        p.setInlineShellEnabled(true);
        String content = "Result: !`nonexistent_command_xyz123`";
        String result = p.preprocess(content, null, "/tmp");
        // Should contain an error marker, not throw
        assertThat(result).startsWith("Result: ");
    }

    @Test
    void inlineShell_relativePathWorks_usesSkillDirAsCwd(@TempDir Path tempDir) throws IOException {
        SkillPreprocessor p = new SkillPreprocessor();
        p.setInlineShellEnabled(true);
        Files.writeString(tempDir.resolve("marker.txt"), "relative-ok");
        String content = "Echo: !`" + readFileCommand("marker.txt") + "`";
        String result = p.preprocess(content, null, tempDir.toString());
        assertThat(result).contains("relative-ok");
    }
}
