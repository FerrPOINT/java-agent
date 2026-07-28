package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
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

class AgentCliRunnerTest {

    @Test
    void runLoopExitsOnExitCommand() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentCliRunner runner = new AgentCliRunner(runtime);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("hello", "exit");
        when(runtime.runTurn(any(Session.class), any())).thenReturn(new TurnResult(List.of(Message.user("hello"), Message.assistant("hi back", 1)), true, null));

        List<String> output = new ArrayList<>();
        Session session = Session.create("cli-user", "noop", "");
        runner.runLoop(reader, session, output::add);

        assertThat(output).containsExactly(
            "Agent CLI. Type 'exit' to quit.",
            "hi back",
            "Goodbye."
        );
    }

    @Test
    void runLoopHandlesNullInput() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentCliRunner runner = new AgentCliRunner(runtime);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn(null);

        List<String> output = new ArrayList<>();
        runner.runLoop(reader, Session.create("cli-user", "noop", ""), output::add);

        assertThat(output).containsExactly(
            "Agent CLI. Type 'exit' to quit.",
            "Goodbye."
        );
    }

    @Test
    void runLoopHandlesEmptyInput() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentCliRunner runner = new AgentCliRunner(runtime);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("", "exit");

        List<String> output = new ArrayList<>();
        runner.runLoop(reader, Session.create("cli-user", "noop", ""), output::add);

        assertThat(output).containsExactly(
            "Agent CLI. Type 'exit' to quit.",
            "Goodbye."
        );
    }
}
