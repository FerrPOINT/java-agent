package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentCliRunnerBuilderTest {

    @Test
    void buildsTerminal() throws Exception {
        AgentCliRunner runner = new AgentCliRunner(mock(AgentRuntime.class));
        Terminal terminal = runner.buildTerminal();
        assertThat(terminal).isNotNull();
        terminal.close();
    }

    @Test
    void buildsReaderFromTerminal() throws Exception {
        AgentCliRunner runner = new AgentCliRunner(mock(AgentRuntime.class));
        try (Terminal terminal = runner.buildTerminal()) {
            LineReader reader = runner.buildReader(terminal);
            assertThat(reader).isNotNull();
            assertThat(reader.getAppName()).isEqualTo("java-agent");
        }
    }
}
