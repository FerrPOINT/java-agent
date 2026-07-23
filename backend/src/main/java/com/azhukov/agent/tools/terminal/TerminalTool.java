package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@AgentTool(
    name = "terminal",
    description = "Run a shell command locally and return stdout/stderr.",
    toolset = "cli"
)
@Component
public class TerminalTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TerminalArgs args = ToolHandler.parseJson(arguments, TerminalArgs.class);
        return runCommand(args.command(), args.timeout());
    }

    private ToolResult runCommand(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("Command timed out after " + timeoutSeconds + " seconds");
            }
            String output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(java.util.stream.Collectors.joining("\n"));
            return ToolResult.ok(output);
        } catch (Exception e) {
            return ToolResult.fail("Failed to execute command: " + e.getMessage());
        }
    }

    public record TerminalArgs(
        @ToolParam(description = "shell command to execute") String command,
        @ToolParam(description = "timeout in seconds") int timeout
    ) {}
}
