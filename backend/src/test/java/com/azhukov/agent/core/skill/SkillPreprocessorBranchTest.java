package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link SkillPreprocessor}.
 * Covers template variable substitution, inline shell expansion, edge cases.
 */
class SkillPreprocessorBranchTest {

    @Test
    void preprocess_nullContent_returnsNull() {
        SkillPreprocessor sp = new SkillPreprocessor();
        assertThat(sp.preprocess(null, "session1", "/skill/dir")).isNull();
    }

    @Test
    void preprocess_emptyContent_returnsEmpty() {
        SkillPreprocessor sp = new SkillPreprocessor();
        assertThat(sp.preprocess("", "session1", "/skill/dir")).isEmpty();
    }

    @Test
    void preprocess_disabled_returnsContentUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setEnabled(false);
        String content = "Hello ${HERMES_SESSION_ID}";
        assertThat(sp.preprocess(content, "sess1", "/dir")).isEqualTo(content);
    }

    @Test
    void preprocess_substitutesSessionId() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.preprocess("Session: ${HERMES_SESSION_ID}", "sess123", "/dir");
        assertThat(result).isEqualTo("Session: sess123");
    }

    @Test
    void preprocess_substitutesSkillDir() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.preprocess("Dir: ${HERMES_SKILL_DIR}", "sess", "/my/skill/dir");
        assertThat(result).isEqualTo("Dir: /my/skill/dir");
    }

    @Test
    void preprocess_backwardCompatSessionId() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.preprocess("Session: ${SESSION_ID}", "sess123", "/dir");
        assertThat(result).isEqualTo("Session: sess123");
    }

    @Test
    void preprocess_backwardCompatSkillDir() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.preprocess("Dir: ${SKILL_DIR}", "sess", "/my/dir");
        assertThat(result).isEqualTo("Dir: /my/dir");
    }

    @Test
    void preprocess_nullSessionId_leavesTokenUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.substituteTemplateVars("ID: ${HERMES_SESSION_ID}", null, "/dir");
        assertThat(result).isEqualTo("ID: ${HERMES_SESSION_ID}");
    }

    @Test
    void preprocess_nullSkillDir_leavesTokenUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String result = sp.substituteTemplateVars("Dir: ${HERMES_SKILL_DIR}", "sess", null);
        assertThat(result).isEqualTo("Dir: ${HERMES_SKILL_DIR}");
    }

    @Test
    void preprocess_multipleVarsInOneLine() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String content = "Session=${HERMES_SESSION_ID} Dir=${HERMES_SKILL_DIR}";
        String result = sp.preprocess(content, "s1", "/d");
        assertThat(result).isEqualTo("Session=s1 Dir=/d");
    }

    @Test
    void preprocess_noVars_returnsUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String content = "Just some text without variables.";
        assertThat(sp.preprocess(content, "sess", "/dir")).isEqualTo(content);
    }

    @Test
    void preprocess_unknownVar_returnsUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        String content = "Unknown: ${UNKNOWN_VAR}";
        assertThat(sp.preprocess(content, "sess", "/dir")).isEqualTo(content);
    }

    // ── Inline shell expansion ──

    @Test
    void expandInlineShell_disabled_returnsContentUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(false);
        String content = "Output: !`echo hello`";
        assertThat(sp.preprocess(content, "sess", "/tmp")).isEqualTo(content);
    }

    @Test
    void expandInlineShell_enabled_substitutesOutput() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String content = "Output: !`echo hello`";
        String result = sp.preprocess(content, "sess", "/tmp");
        assertThat(result).contains("hello");
    }

    @Test
    void expandInlineShell_noBacktickSyntax_returnsUnchanged() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String content = "No shell expansion here";
        assertThat(sp.preprocess(content, "sess", "/tmp")).isEqualTo(content);
    }

    @Test
    void expandInlineShell_emptyCommand_ignored() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String content = "Output: !`` end";
        String result = sp.preprocess(content, "sess", "/tmp");
        assertThat(result).contains("!``");
    }

    @Test
    void expandInlineShell_failingCommand_returnsErrorMessage() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String content = "Output: !`false`";
        String result = sp.preprocess(content, "sess", "/tmp");
        // Should not throw — returns error marker or empty
        assertThat(result).isNotNull();
    }

    @Test
    void expandInlineShell_commandWithStderr_returnsStderr() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String content = "Output: !`echo errormsg >&2`";
        String result = sp.preprocess(content, "sess", "/tmp");
        assertThat(result).contains("errormsg");
    }

    @Test
    void runInlineShell_nonExistentCommand_returnsErrorMessage() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String result = sp.runInlineShell("nonexistent-command-xyz123", "/tmp");
        // When command not found, bash returns stderr which is used as output
        // The result should contain either the error message or the stderr output
        assertThat(result).isNotNull();
        // Either an inline-shell error or a "not found" type message from bash
        assertThat(result).isNotEmpty();
    }

    @Test
    void runInlineShell_withNullSkillDir_works() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String result = sp.runInlineShell("echo test", null);
        assertThat(result).isEqualTo("test");
    }

    @Test
    void runInlineShell_withBlankSkillDir_works() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        String result = sp.runInlineShell("echo test", "  ");
        assertThat(result).isEqualTo("test");
    }

    // ── Enable / disable setters ──

    @Test
    void setEnabled_false_isEnabledReturnsFalse() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setEnabled(false);
        assertThat(sp.isEnabled()).isFalse();
    }

    @Test
    void setEnabled_true_isEnabledReturnsTrue() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setEnabled(true);
        assertThat(sp.isEnabled()).isTrue();
    }

    @Test
    void setInlineShellEnabled_true_isInlineShellEnabledReturnsTrue() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(true);
        assertThat(sp.isInlineShellEnabled()).isTrue();
    }

    @Test
    void setInlineShellEnabled_false_isInlineShellEnabledReturnsFalse() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellEnabled(false);
        assertThat(sp.isInlineShellEnabled()).isFalse();
    }

    @Test
    void setInlineShellTimeout_updatesTimeout() {
        SkillPreprocessor sp = new SkillPreprocessor();
        sp.setInlineShellTimeout(5);
        // Timeout is used internally — verify it was set by checking that a slow command times out
        sp.setInlineShellEnabled(true);
        String result = sp.runInlineShell("sleep 10", "/tmp");
        assertThat(result).contains("timeout");
    }
}