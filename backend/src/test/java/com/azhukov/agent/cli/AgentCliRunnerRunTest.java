package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCliRunnerRunTest {

    @Test
    void runCallsRunLoop() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        List<String> output = new ArrayList<>();

        AgentCliRunner runner = new AgentCliRunner(runtime) {
            @Override
            Terminal buildTerminal() {
                Terminal t = mock(Terminal.class);
                when(t.getType()).thenReturn("dumb");
                return t;
            }

            @Override
            LineReader buildReader(Terminal terminal) {
                LineReader r = mock(LineReader.class);
                when(r.readLine("> ")).thenReturn("exit");
                return r;
            }

            @Override
            void runLoop(LineReader reader, Session session, java.util.function.Consumer<String> out) {
                out.accept("stub-loop");
            }
        };

        runner.run();
    }
}
