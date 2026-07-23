package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

@AgentTool(
    name = "terminal",
    description = "Run a shell command locally and return stdout/stderr. For long-lived processes use background=true to get a session_id, then manage it with the process tool. Blocked commands: rm -rf /, mkfs, dd if=/dev/zero, :(){ :|:& };:.",
    toolset = "terminal"
)
@Component
public class TerminalTool implements ToolHandler {

    private static final List<String> BLOCKED_PATTERNS = List.of(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=/dev/zero", ":(){ :|:\u0026 };:"
    );

    private final ProcessTool processTool;

    public TerminalTool(ProcessTool processTool) {
        this.processTool = processTool;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TerminalArgs args = ToolHandler.parseJson(arguments, TerminalArgs.class);
        if (args.command() == null || args.command().isBlank()) {
            return ToolResult.fail("Command is required");
        }
        String command = args.command();
        for (String pattern : BLOCKED_PATTERNS) {
            if (command.contains(pattern)) {
                return ToolResult.fail("Blocked dangerous command pattern: " + pattern);
            }
        }
        int timeout = args.timeout() > 0 ? args.timeout() : 30;

        if (args.background()) {
            try {
                ProcessTool.ManagedProcess mp = processTool.spawn(command, timeout);
                return ToolResult.ok(String.format(
                    "Background process started\nsession_id: %s\npid: %s",
                    mp.id, mp.pid
                ));
            } catch (Exception e) {
                return ToolResult.fail("Failed to start background process: " + e.getMessage());
            }
        }

        return runCommand(command, timeout);
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

    public static class TerminalArgs {
        @JsonProperty("command")
        @ToolParam(description = "shell command to execute")
        private String command;
        @JsonProperty("timeout")
        @ToolParam(description = "timeout in seconds", required = false)
        private int timeout;
        @JsonProperty("background")
        @ToolParam(description = "run as background process and return session_id", required = false)
        private boolean background;

        public String command() { return command; }
        public int timeout() { return timeout; }
        public boolean background() { return background; }
    }
}
