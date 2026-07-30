package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentCliRunner implements CommandLineRunner {

    private final AgentRuntime agentRuntime;


    @Override
    public void run(String... args) throws Exception {
        Terminal terminal = buildTerminal();
        LineReader reader = buildReader(terminal);
        runLoop(reader, Session.create("cli-user", "openai-compatible", ""), System.out::println);
    }

    Terminal buildTerminal() throws Exception {
        return TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .build();
    }

    LineReader buildReader(Terminal terminal) {
        return LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("java-agent")
            .build();
    }

    void runLoop(LineReader reader, Session session, java.util.function.Consumer<String> output) {
        output.accept("Agent CLI. Type 'exit' to quit.");
        while (true) {
            try {
                String line = reader.readLine("> ");
                if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                TurnResult result = agentRuntime.runTurn(session, line);
                output.accept(result.finalText());
            } catch (Exception e) {
                output.accept("Error: " + e.getMessage());
            }
        }
        output.accept("Goodbye.");
    }
}