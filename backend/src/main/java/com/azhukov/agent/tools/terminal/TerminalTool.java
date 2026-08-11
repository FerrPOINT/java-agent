package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
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
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@AgentTool(
    name = "terminal",
    description = "Run a shell command locally and return stdout/stderr. For long-lived processes use background=true to get a session_id, then manage it with the process tool. Dangerous commands (rm -rf /, mkfs, dd, sudo, fork bombs, etc.) are blocked using regex patterns; the blocked list is configurable via agent.security.blocked-commands and agent.terminal.block-sudo.",
    toolset = "terminal"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class TerminalTool implements ToolHandler {

    private final ProcessTool processTool;
    private final AgentProperties properties;
    private final Redactor redactor;
    private final CheckpointManager checkpointManager;
    private final InterruptToken interruptToken;

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
        boolean blockSudo = properties.getTerminal().isBlockSudo();
        CommandGuard guard = new CommandGuard(blockedPatterns, blockSudo);
        String blockReason = guard.check(command);
        if (blockReason != null) {
            return ToolResult.fail(blockReason);
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

        UUID sessionId = session != null ? session.id() : null;
        return runCommand(command, timeout, sessionId);
    }

    private ToolResult runCommand(String command, int timeoutSeconds, UUID sessionId) {
        Process process = null;
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            process = pb.start();

            // Register a cancellation callback so that if the user interrupts
            // during this long-running command, the process is killed immediately.
            final Process managedProcess = process;
            if (interruptToken != null && sessionId != null) {
                Runnable callback = () -> {
                    interrupted.set(true);
                    try {
                        managedProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                        managedProcess.destroyForcibly();
                    } catch (Exception e) {
                        log.debug("Failed to destroy process on interrupt: {}", e.getMessage());
                    }
                };
                interruptToken.registerCancellationCallback(sessionId, callback);
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            // Clean up the callback registration
            if (interruptToken != null && sessionId != null) {
                interruptToken.unregister(sessionId);
            }

            if (interrupted.get()) {
                return ToolResult.fail("Interrupted by user");
            }

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("Command timed out after " + timeoutSeconds + " seconds");
            }
            String output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(java.util.stream.Collectors.joining("\n"));
            return ToolResult.ok(redact(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (interruptToken != null && sessionId != null) {
                interruptToken.unregister(sessionId);
            }
            return ToolResult.fail("Interrupted by user");
        } catch (Exception e) {
            if (interruptToken != null && sessionId != null) {
                interruptToken.unregister(sessionId);
            }
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