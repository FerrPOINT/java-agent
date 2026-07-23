package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolTest {

    private final TerminalTool tool = new TerminalTool(new ProcessTool());

    @Test
    void echoesCommand() {
        var result = tool.execute("{\"command\":\"echo hello\"}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualToIgnoringWhitespace("hello");
    }

    @Test
    void failsWhenCommandMissing() {
        var result = tool.execute("{}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command is required");
    }

    @Test
    void blocksDangerousCommand() {
        var result = tool.execute("{\"command\":\"rm -rf /\"}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Blocked");
    }

    @Test
    void usesCustomTimeout(@TempDir Path temp) {
        // Sleep 1s with explicit timeout 10s should succeed
        var result = tool.execute("{\"command\":\"sleep 1; echo done\",\"timeout\":10}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("done");
    }

    @Test
    void timesOut(@TempDir Path temp) {
        var result = tool.execute("{\"command\":\"sleep 10; echo done\",\"timeout\":1}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timed out");
    }
}
