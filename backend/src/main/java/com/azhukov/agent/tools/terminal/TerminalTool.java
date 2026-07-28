package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@AgentTool(
    name = "terminal",
    description = "Run a shell command locally and return stdout/stderr. For long-lived processes use background=true to get a session_id, then manage it with the process tool. Blocked command patterns are configurable; default blocks rm -rf /, mkfs, dd if=/dev/zero, fork bombs.",
    toolset = "terminal"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalTool implements ToolHandler {

    private static final List<String> DEFAULT_BLOCKED_PATTERNS = List.of(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=/dev/zero", ":(){ :|:& };:"
    );

    private final ProcessTool processTool;
    private final AgentProperties properties;
    private final Redactor redactor;
    private final CheckpointManager checkpointManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TerminalArgs args = ToolHandler.parseJson(arguments, TerminalArgs.class);
        // TerminalArgs is package-private inner record defined below
        if (args.command() == null || args.command().isBlank()) {
            return ToolResult.fail("Command is required");
        }
        String command = args.command();

        // Auto-checkpoint before dangerous commands
        if (properties.getCheckpoints().isEnabled() && checkpointManager.isDangerousCommand(command)) {
            try {
                checkpointManager.snapshot("Auto-checkpoint before: " + command);
                log.info("Auto-checkpoint created before dangerous command: {}", command);
            } catch (Exception e) {
                log.warn("Auto-checkpoint failed: {}", e.getMessage());
            }
        }

        List<String> blockedPatterns = properties.getSecurity().getBlockedCommands();
        if (blockedPatterns == null || blockedPatterns.isEmpty()) {
            blockedPatterns = DEFAULT_BLOCKED_PATTERNS;
        }
        for (String pattern : blockedPatterns) {
            if (command.contains(pattern)) {
                return ToolResult.fail("Blocked dangerous command pattern: " + pattern);
            }
        }

        int timeout = args.timeout() > 0 ? args.timeout() : properties.getTerminal().getDefaultTimeoutSeconds();
        timeout = Math.min(timeout, properties.getTerminal().getMaxTimeoutSeconds());

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
            return ToolResult.ok(redact(output));
        } catch (Exception e) {
            return ToolResult.fail("Failed to execute command: " + e.getMessage());
        }
    }

    private String redact(String output) {
        return redactor.redact(output);
    }

    record TerminalArgs(String command, int timeout, boolean background) {
        TerminalArgs {
            if (command == null) command = "";
            if (timeout < 0) timeout = 0;
        }
    }
}
