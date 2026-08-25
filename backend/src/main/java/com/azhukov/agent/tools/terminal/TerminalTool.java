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
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    description = "Execute shell commands on a Linux environment. Filesystem, current working directory, and exported environment variables persist between calls.\n\nDo NOT use cat/head/tail (use read_file), grep/rg/find/ls (use search_files), sed/awk (use patch), or echo/heredoc file creation (use write_file). Reserve terminal for: builds, installs, git, processes, scripts, network, package managers, and anything that needs a shell.\nNEVER pipe a build/test command through tail/head/cat to shorten output (e.g. `cargo build | tail -20`): output is auto-truncated with the full text saved to a file, and the pipe makes exit_code report the LAST pipeline command's status (tail's 0), masking real failures. Run the command bare; the same applies to `cmd || echo failed`, which also masks the exit code.\nEnvironment state persists: activate a virtualenv or export variables once per session, not before every command.\n\nForeground (default): returns INSTANTLY when the command finishes, even with a high timeout — set timeout generously for long builds.\nBackground: set background=true (returns a session_id). Pair with notify_on_complete=true for bounded tasks; leave silent only for servers/daemons that never exit. Never use nohup/setsid/trailing '&' — use background=true so Hermes tracks the process. After starting a server, verify readiness with a health check, then act in a separate call; no blind sleep loops. Manage with process(action=\"poll\"/\"wait\").\nWorking directory: use 'workdir' for per-command cwd. When a command changes the session cwd (cd, pushd), the result includes a \"cwd\" field — trust it instead of prefixing every command with 'cd'.\nPTY: set pty=true for interactive CLIs (they hang without it). Pipe git output to cat if it might page.",
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
                // BUG 4: Pass workdir to spawn() — was previously ignored for background processes
                // Feature 3: Pass notifyOnComplete callback when notify_on_complete=true
                java.util.function.Consumer<String> notifyCallback = null;
                if (args.notifyOnComplete()) {
                    notifyCallback = processId -> {
                        log.info("Background process {} completed (notify_on_complete)", processId);
                        // The actual notification delivery is handled by ProcessTool's exit watcher;
                        // this callback ensures the process is tracked for completion notifications.
                    };
                }
                ProcessTool.ManagedProcess mp = processTool.spawn(command, timeout, args.pty(), notifyCallback, args.workdir());
                String result = String.format(
                    "Background process started\nsession_id: %s\npid: %s",
                    mp.id, mp.pid
                );
                // Feature 3: If watch_patterns are specified, start a pattern monitor
                if (args.watchPatterns() != null && !args.watchPatterns().isEmpty()) {
                    result += "\nwatch_patterns: " + String.join(", ", args.watchPatterns());
                    startWatchPatternMonitor(mp, args.watchPatterns());
                }
                if (args.notifyOnComplete()) {
                    result += "\nnotify_on_complete: enabled";
                }
                return ToolResult.ok(result);
            } catch (Exception e) {
                return ToolResult.fail("Failed to start background process: " + e.getMessage());
            }
        }

        UUID sessionId = session != null ? session.id() : null;
        return runCommand(command, timeout, sessionId, args.pty(), args.workdir(), guard);
    }

    private ToolResult runCommand(String command, int timeoutSeconds, UUID sessionId, boolean usePty, String workdir,
                                  CommandGuard guard) {
        Process process = null;
        AtomicBoolean interrupted = new AtomicBoolean(false);
        // Read output concurrently with waitFor() to avoid losing buffered data
        // when the PTY closes the stream before all output is flushed (Finding 1.4).
        StringBuilder outputBuffer = new StringBuilder();
        Thread outputReader = null;
        try {
            ProcessBuilder pb;
            if (usePty) {
                // PTY mode: use 'script' to allocate a pseudo-terminal.
                // 'script -qec "command" /dev/null' runs the command in a PTY
                // silently (quiet, no typescript file to /dev/null).
                pb = new ProcessBuilder("script", "-qec", command, "/dev/null");
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }
            // Set working directory if provided
            // h49: If the configured workdir doesn't exist or isn't a directory,
            // fall back to /tmp or user home instead of hard-failing.
            String actualCwd = null;
            if (workdir != null && !workdir.isBlank()) {
                java.io.File dir = new java.io.File(workdir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                    actualCwd = dir.getAbsolutePath();
                } else {
                    // Fallback: resolve an accessible directory
                    java.io.File fallback = TerminalOutputEnhancer.resolveWorkdirFile(workdir);
                    if (fallback != null) {
                        pb.directory(fallback);
                        actualCwd = fallback.getAbsolutePath();
                        log.warn("Configured workdir '{}' is not accessible, using '{}' instead", workdir, actualCwd);
                    }
                    // If even fallback is null, pb.directory stays at JVM default
                }
            }
            pb.redirectErrorStream(true);
            process = pb.start();

            // Start reading output in a separate thread concurrent with waitFor()
            // to avoid losing buffered data when the stream closes (Finding 1.4).
            final Process procForReader = process;
            outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(procForReader.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuffer.append(line).append('\n');
                    }
                } catch (java.io.IOException e) {
                    // Stream closed — expected when process exits
                }
            }, "terminal-output-" + process.pid());
            outputReader.setDaemon(true);
            outputReader.start();

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
                // Wait briefly for the output reader to drain any remaining output
                if (outputReader != null) {
                    outputReader.join(2000);
                }
                String partialOutput = redact(AnsiStrip.strip(stripTrailingNewline(outputBuffer.toString(), usePty)));
                String enhanced = TerminalOutputEnhancer.enhance(
                    partialOutput, -1, workdir, true, actualCwd);
                return ToolResult.fail("Command timed out after " + timeoutSeconds + " seconds" + enhanced);
            }

            // Wait for the output reader thread to finish draining the stream
            if (outputReader != null) {
                outputReader.join(5000);
            }

            String output = outputBuffer.toString();
            output = stripTrailingNewline(output, usePty);
            // Hermes parity (tools/terminal_tool.py:3466): strip ANSI escapes from ALL
            // terminal output — full ECMA-48 coverage (CSI private-mode, OSC, DCS, 8-bit C1).
            output = AnsiStrip.strip(output);
            String redactedOutput = redact(output);

            // Finding 1.3: Call notifyPostExecution after process completes
            int exitCode = process.exitValue();
            guard.notifyPostExecution(command, exitCode, redactedOutput);

            // p4/p5/h47/h48: Enhance output with CWD echo, error hints, signal info,
            // and masked-failure warnings.
            String enhanced = TerminalOutputEnhancer.enhance(
                redactedOutput, exitCode, workdir, false, actualCwd);

            // Hermes parity (display.py _detect_tool_failure:1350-1358): a non-zero
            // exit code is the CANONICAL failure signal — the loop guardrail and
            // the result classifier both key off result.success. Returning ok()
            // for failed commands made failure-loop detection blind for terminal.
            if (exitCode != 0) {
                return new ToolResult(false, enhanced, "exit " + exitCode);
            }
            return ToolResult.ok(enhanced);
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

    /**
     * Strips the trailing newline added by the reader loop and normalises
     * PTY {@code \r\n} line endings to {@code \n}.
     */
    private static String stripTrailingNewline(String output, boolean usePty) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        if (usePty) {
            output = output.replace("\r\n", "\n").replace("\r", "\n");
        }
        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }

    /**
     * Feature 3: Monitor a background process output for watch patterns.
     * When any pattern is found in the output, logs a notification.
     * Rate-limited to 1 notification per 15 seconds per process.
     */
    private void startWatchPatternMonitor(ProcessTool.ManagedProcess mp, List<String> patterns) {
        Thread monitor = new Thread(() -> {
            long lastNotifyTime = 0;
            long startTime = System.currentTimeMillis();
            long maxDuration = 30 * 60 * 1000L; // 30 minutes max monitoring
            while (mp.process.isAlive() && (System.currentTimeMillis() - startTime) < maxDuration) {
                try {
                    Thread.sleep(500); // Poll every 500ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String recentOutput = mp.getRecentOutput(500);
                for (String pattern : patterns) {
                    if (recentOutput.contains(pattern)) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotifyTime > 15_000) { // 15s rate limit
                            log.info("Watch pattern '{}' matched in process {} output", pattern, mp.id);
                            lastNotifyTime = now;
                        }
                        break; // One pattern match per poll cycle
                    }
                }
            }
        }, "watch-pattern-" + mp.id);
        monitor.setDaemon(true);
        monitor.start();
    }

    record TerminalArgs(
        @ToolParam(description = "The command to execute on the VM") String command,
        @ToolParam(description = "Max seconds to wait (default 180, foreground max 600).", required = false) int timeout,
        @ToolParam(description = "Run in the background, returning a session_id.", required = false) boolean background,
        @ToolParam(description = "Run in pseudo-terminal for interactive CLIs.", required = false) boolean pty,
        @ToolParam(description = "Working directory for this command.", required = false) String workdir,
        @JsonProperty("notify_on_complete") @JsonAlias("notify-on-complete") @ToolParam(description = "Get notified when the process exits.", required = false) boolean notifyOnComplete,
        @JsonProperty("watch_patterns") @JsonAlias("watch-patterns") @ToolParam(description = "Strings to watch for in background output.", required = false) List<String> watchPatterns) {
        TerminalArgs {
            if (command == null) command = "";
            if (timeout < 0) timeout = 0;
            if (watchPatterns == null) watchPatterns = List.of();
        }
    }
}