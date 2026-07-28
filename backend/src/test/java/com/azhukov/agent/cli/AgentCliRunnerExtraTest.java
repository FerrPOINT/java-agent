package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCliRunnerExtraTest {

    @Test
    void runLoopUsesReferencesWhenProvided() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentCliRunner runner = new AgentCliRunner(runtime);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("exit");
        when(runtime.runTurn(any(), any())).thenReturn(new TurnResult(List.of(), true, null));

        List<String> output = new ArrayList<>();
        runner.runLoop(reader, Session.create("cli-user", "noop", ""), output::add);

        assertThat(output).containsExactly("Agent CLI. Type 'exit' to quit.", "Goodbye.");
    }
}
