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

@Component
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true")
public class AgentCliRunner implements CommandLineRunner {

    private final AgentRuntime agentRuntime;

    public AgentCliRunner(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public void run(String... args) throws Exception {
        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .build();
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("java-agent")
            .build();

        Session session = Session.create("cli-user", "openai-compatible", "");
        System.out.println("Agent CLI. Type 'exit' to quit.");
        while (true) {
            String line = reader.readLine("> ");
            if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                break;
            }
            TurnResult result = agentRuntime.runTurn(session, line);
            System.out.println(result.finalText());
        }
    }
}
