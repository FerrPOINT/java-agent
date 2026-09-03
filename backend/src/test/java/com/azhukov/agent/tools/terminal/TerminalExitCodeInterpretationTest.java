package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity tests for _interpret_exit_code: non-zero exits that are
 * normal semantics (grep=1 no matches, diff=1 files differ) must be
 * interpreted, not classified as failures.
 */
class TerminalExitCodeInterpretationTest {

    @Test
    void grepExit1MeansNoMatches() {
        String note = TerminalOutputEnhancer.interpretExitCode("grep -r foo /src", 1);
        assertThat(note).contains("No matches found");
    }

    @Test
    void ripgrepExit1MeansNoMatches() {
        String note = TerminalOutputEnhancer.interpretExitCode("rg pattern .", 1);
        assertThat(note).contains("No matches found");
    }

    @Test
    void grepExit2IsRealError() {
        String note = TerminalOutputEnhancer.interpretExitCode("grep -r foo /src", 2);
        assertThat(note).isNull();
    }

    @Test
    void diffExit1MeansFilesDiffer() {
        String note = TerminalOutputEnhancer.interpretExitCode("diff a.txt b.txt", 1);
        assertThat(note).contains("Files differ");
    }

    @Test
    void pipelineTakesLastSegment() {
        // grep is the last command in the pipeline
        String note = TerminalOutputEnhancer.interpretExitCode("cat file | grep foo", 1);
        assertThat(note).contains("No matches found");
    }

    @Test
    void pipelineLastCommandRealError() {
        // cat fails with 1? No - cat exit codes: 1 = real error
        String note = TerminalOutputEnhancer.interpretExitCode("grep foo file | cat", 1);
        assertThat(note).isNull(); // cat has no special semantics for 1
    }

    @Test
    void envVarPrefixSkipped() {
        String note = TerminalOutputEnhancer.interpretExitCode("LC_ALL=C grep foo file", 1);
        assertThat(note).contains("No matches found");
    }

    @Test
    void absolutePathStripped() {
        String note = TerminalOutputEnhancer.interpretExitCode("/usr/bin/grep foo file", 1);
        assertThat(note).contains("No matches found");
    }

    @Test
    void curlExit22IsHttpError() {
        String note = TerminalOutputEnhancer.interpretExitCode("curl https://example.com", 22);
        assertThat(note).contains("HTTP response code");
    }

    @Test
    void curlExit7IsConnectFail() {
        String note = TerminalOutputEnhancer.interpretExitCode("curl https://example.com", 7);
        assertThat(note).contains("Failed to connect");
    }

    @Test
    void gitExit1IsOftenNormal() {
        String note = TerminalOutputEnhancer.interpretExitCode("git diff", 1);
        assertThat(note).contains("often normal");
    }

    @Test
    void testExit1IsFalseCondition() {
        String note = TerminalOutputEnhancer.interpretExitCode("test -f /nonexistent", 1);
        assertThat(note).contains("Condition evaluated to false");
    }

    @Test
    void exit0ReturnsNull() {
        String note = TerminalOutputEnhancer.interpretExitCode("grep foo file", 0);
        assertThat(note).isNull();
    }

    @Test
    void unknownCommandReturnsNull() {
        String note = TerminalOutputEnhancer.interpretExitCode("ls -la", 1);
        assertThat(note).isNull();
    }
}
