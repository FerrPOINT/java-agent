package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolTest {

    private final TerminalTool tool;

    TerminalToolTest() {
        AgentProperties properties = new AgentProperties();
        this.tool = new TerminalTool(new ProcessTool(), properties, new com.azhukov.agent.core.security.DefaultRedactor(properties));
    }

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
}
