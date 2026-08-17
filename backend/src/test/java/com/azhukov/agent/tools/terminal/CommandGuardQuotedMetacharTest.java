package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h92: Tests for quote-aware shell metacharacter detection in CommandGuard.
 * Metacharacters inside single or double quotes are literal, not operators.
 */
class CommandGuardQuotedMetacharTest {

    @Test
    void stripQuotedSegments_singleQuoteReplacesContent() {
        String result = CommandGuard.stripQuotedSegments("grep 'a|b' file");
        assertThat(result).isEqualTo("grep _QUOTE_ file");
    }

    @Test
    void stripQuotedSegments_doubleQuoteReplacesContent() {
        String result = CommandGuard.stripQuotedSegments("grep \"a|b\" file");
        assertThat(result).isEqualTo("grep _QUOTE_ file");
    }

    @Test
    void stripQuotedSegments_multipleQuotedSegments() {
        String result = CommandGuard.stripQuotedSegments("echo 'hello' | grep 'world'");
        assertThat(result).isEqualTo("echo _QUOTE_ | grep _QUOTE_");
    }

    @Test
    void stripQuotedSegments_noQuotesUnchanged() {
        String result = CommandGuard.stripQuotedSegments("echo hello | grep world");
        assertThat(result).isEqualTo("echo hello | grep world");
    }

    @Test
    void stripQuotedSegments_emptyString() {
        assertThat(CommandGuard.stripQuotedSegments("")).isEqualTo("");
    }

    @Test
    void stripQuotedSegments_nullInput() {
        assertThat(CommandGuard.stripQuotedSegments(null)).isNull();
    }

    @Test
    void shellTokens_quotedPipeNotSplitAsOperator() {
        String[] tokens = CommandGuard.shellTokens("grep 'a|b' file");
        // 'grep', _QUOTE_ (from 'a|b'), 'file' — the pipe inside quotes
        // should NOT create a token boundary
        assertThat(tokens).contains("grep", "file");
        // The quoted segment should not produce "a" and "b" as separate tokens
        for (String token : tokens) {
            assertThat(token).doesNotContain("|");
        }
    }

    @Test
    void shellTokens_quotedSemicolonNotSplitAsOperator() {
        String[] tokens = CommandGuard.shellTokens("echo 'a;b' file");
        assertThat(tokens).contains("echo", "file");
        for (String token : tokens) {
            assertThat(token).doesNotContain(";");
        }
    }

    @Test
    void shellTokens_unquotedPipeStillSplit() {
        String[] tokens = CommandGuard.shellTokens("echo hello | grep world");
        // Unquoted pipe should still create a token boundary
        assertThat(tokens).contains("echo", "hello", "grep", "world");
    }

    @Test
    void shellTokens_doubleQuotedPipeNotSplit() {
        String[] tokens = CommandGuard.shellTokens("grep \"a|b\" file");
        assertThat(tokens).contains("grep", "file");
        for (String token : tokens) {
            assertThat(token).doesNotContain("|");
        }
    }

    @Test
    void shellTokens_mixedQuotedAndUnquoted() {
        String[] tokens = CommandGuard.shellTokens("echo 'safe' | grep danger");
        // The unquoted pipe should create a boundary, but the quoted content stays together
        assertThat(tokens).contains("echo", "grep", "danger");
    }
}