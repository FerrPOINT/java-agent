package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.RunControlScope;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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

    // Passes an enforced cron workdir to tools through the isolated session.
    public static final String META_WORKDIR = "cron_workdir";
    /** Hermes parity: FOREGROUND_MAX_TIMEOUT = 600s. Foreground timeout above this is rejected. */
    private static final int FOREGROUND_MAX_TIMEOUT = 600;
    private static final ObjectMapper JSON = SharedObjectMapper.get();
    private static final Pattern SHELL_LEVEL_BACKGROUND_RE = Pattern.compile(
        "(?:^|[;&|]\\s*|&&\\s*|\\|\\|\\s*|\\$\\(\\s*)(?:nohup|disown|setsid)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern INLINE_BACKGROUND_AMP_RE = Pattern.compile("\\s&\\s");
    private static final Pattern TRAILING_BACKGROUND_AMP_RE = Pattern.compile("\\s&\\s*(?:#.*)?$");
    private static final List<Pattern> LONG_LIVED_FOREGROUND_PATTERNS = List.of(
        Pattern.compile("\\b(?:npm|pnpm|yarn|bun)\\s+(?:run\\s+)?(?:dev|start|serve|watch)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bdocker\\s+compose\\s+up\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bnext\\s+dev\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bvite(?:\\s|$)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bnodemon\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\buvicorn\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bgunicorn\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bpython(?:3)?\\s+-m\\s+http\\.server\\b", Pattern.CASE_INSENSITIVE)
    );

    // Hermes parity (terminal_tool.py:1240-1276): track per-session cwd so that
    // `cd /opt/dev` in one call persists to the next. Without this, each
    // ProcessBuilder starts at the JVM default cwd and the tool description's
    // claim "current working directory persists between calls" is a lie.
    private static final java.util.concurrent.ConcurrentHashMap<UUID, String> SESSION_CWD = new java.util.concurrent.ConcurrentHashMap<>();

    // Hermes parity: track per-session exported env vars so that
    // `export FOO=bar` in one call persists to the next.
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.Map<String, String>> SESSION_ENV = new java.util.concurrent.ConcurrentHashMap<>();

    public static String trackedCwd(UUID sessionId) {
        return sessionId == null ? null : SESSION_CWD.get(sessionId);
    }

    static java.util.Map<String, String> trackedEnv(UUID sessionId) {
        java.util.Map<String, String> env = sessionId == null ? null : SESSION_ENV.get(sessionId);
        return env == null ? java.util.Map.of() : env;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TerminalArgs args;
        try {
            args = ToolHandler.parseJson(arguments, TerminalArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        // Hermes parity: models sometimes send execute_code's 'code' argument here.
        // Without this, the call falls through to command=None and fails with
        // "Command is required" — naming neither the stray argument nor the right tool.
        if ((args.command() == null || args.command().isBlank())) {
            // Check if 'code' was sent instead of 'command'
            try {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
                if (tree.has("code") && !tree.get("code").isNull() && !tree.get("code").asText().isBlank()) {
                    return jsonFail(
                        "terminal received a 'code' parameter, but it requires a shell " +
                        "command in 'command'. Use execute_code(code=...) for Python; " +
                        "for shell, retry as terminal(command=...).");
                }
            } catch (Exception ignored) { }
            return jsonFail("Command is required");
        }
        String command = args.command();

        if (!args.background()) {
            if (hasForegroundNotificationModifier(args)) {
                return jsonFail(
                    "notify only applies to background commands (foreground results return directly). " +
                    "Either drop notify, or run as terminal(command=..., background=true, notify=...).");
            }
            if (args.pty()) {
                return jsonFail(
                    "pty requires background=true (a PTY session is interacted with via " +
                    "process(action='write'/'submit'), which needs a tracked background process). " +
                    "Retry as terminal(command=..., background=true, pty=true).");
            }
        }

        NotificationSettings notifications;
        try {
            notifications = resolveNotifications(args);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        if (args.timeout() != null && args.timeout() <= 0) {
            return jsonFail("timeout must be a positive number of seconds (got " + args.timeout() + ").");
        }
        if (!args.background()) {
            String guidance = foregroundBackgroundGuidance(command);
            if (guidance != null) {
                return jsonFail(guidance);
            }
        }

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
            return jsonFail(blockReason);
        }

        int timeout = args.timeout() != null ? args.timeout() : properties.getTerminal().getDefaultTimeoutSeconds();
        // Hermes parity: reject foreground timeout > 600s — nudge toward background.
        if (!args.background() && timeout > FOREGROUND_MAX_TIMEOUT) {
            return jsonFail(
                "Foreground timeout " + timeout + "s exceeds the maximum of "
                + FOREGROUND_MAX_TIMEOUT + "s. Use background=true with "
                + "notify_on_complete=true for long-running commands.");
        }
        timeout = Math.min(timeout, properties.getTerminal().getMaxTimeoutSeconds());
        // A cron workdir is an execution constraint, not advisory prompt text.
        // Hermes parity (terminal_tool.py:2710-2740): if no explicit workdir is
        // given, use the session's tracked cwd (from a previous `cd` command).
        String workdir = args.workdir();
        boolean explicitWorkdir = workdir != null && !workdir.isBlank();
        if ((workdir == null || workdir.isBlank()) && session != null) {
            workdir = session.getMetadata(META_WORKDIR);
        }
        UUID controlSessionId = session != null ? RunControlScope.controlSessionId(session) : null;
        if ((workdir == null || workdir.isBlank()) && controlSessionId != null) {
            String trackedCwd = SESSION_CWD.get(controlSessionId);
            if (trackedCwd != null && !trackedCwd.isBlank()) {
                workdir = trackedCwd;
            }
        }

        if (args.background()) {
            try {
                // Hermes parity (terminal_tool.py:3419-3427): mutual exclusion —
                // if both notify_on_complete and watch_patterns are set, drop
                // watch_patterns. The combination produces duplicate notifications.
                boolean useNotify = notifications.notifyOnComplete();
                var effectiveWatchPatterns = notifications.watchPatterns();
                if (useNotify && effectiveWatchPatterns != null && !effectiveWatchPatterns.isEmpty()) {
                    log.info("watch_patterns ignored because notify_on_complete=true (mutual exclusion)");
                    effectiveWatchPatterns = List.of();
                }

                // BUG 4: Pass workdir to spawn() — was previously ignored for background processes
                // Feature 3: Pass notifyOnComplete callback when notify_on_complete=true
                java.util.function.Consumer<String> notifyCallback = null;
                if (useNotify) {
                    notifyCallback = processId -> {
                        log.info("Background process {} completed (notify_on_complete)", processId);
                    };
                }
                ProcessTool.ManagedProcess mp = processTool.spawn(
                    command, timeout, args.pty(), notifyCallback, workdir, savedEnvForSession(controlSessionId),
                    controlSessionId, effectiveWatchPatterns);
                String result = String.format(
                    "Background process started\nsession_id: %s\npid: %s",
                    mp.id, mp.pid
                );
                if (effectiveWatchPatterns != null && !effectiveWatchPatterns.isEmpty()) {
                    result += "\nwatch_patterns: " + String.join(", ", effectiveWatchPatterns);
                    startWatchPatternMonitor(mp, effectiveWatchPatterns);
                }
                if (useNotify) {
                    result += "\nnotify_on_complete: enabled";
                } else if (effectiveWatchPatterns == null || effectiveWatchPatterns.isEmpty()) {
                    // Hermes parity nudge (terminal_tool.py:3259-3279): background
                    // without notify_on_complete or watch_patterns runs silently.
                    result += "\n[hint: background=true without notify_on_complete=true means "
                        + "you won't know when it finishes. Add notify_on_complete=true, or "
                        + "call process(action=\"poll\") to check.]";
                }
                return ToolResult.ok(result);
            } catch (Exception e) {
                return jsonFail("Failed to start background process: " + e.getMessage());
            }
        }

        return runCommand(command, timeout, controlSessionId, args.pty(), workdir, !explicitWorkdir, guard);
    }

    private java.util.Map<String, String> savedEnvForSession(UUID sessionId) {
        if (sessionId == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> savedEnv = SESSION_ENV.get(sessionId);
        return savedEnv == null ? java.util.Map.of() : savedEnv;
    }

    private static String foregroundBackgroundGuidance(String command) {
        if (looksLikeHelpOrVersionCommand(command)) {
            return null;
        }
        String unquoted = stripQuotedContent(command);
        if (SHELL_LEVEL_BACKGROUND_RE.matcher(unquoted).find()) {
            return "Foreground command uses shell-level background wrappers (nohup/disown/setsid). "
                + "Re-send WITHOUT the wrapper as terminal(command=\"<cmd>\", background=true, "
                + "notify_on_complete=true) so Hermes tracks the process, then run readiness "
                + "checks and tests in separate commands.";
        }
        if (INLINE_BACKGROUND_AMP_RE.matcher(unquoted).find()
            || TRAILING_BACKGROUND_AMP_RE.matcher(unquoted).find()) {
            return "Foreground command uses '&' backgrounding. Re-send WITHOUT the '&' as "
                + "terminal(command=\"<cmd>\", background=true) - add notify_on_complete=true "
                + "for bounded jobs - then run health checks and tests in follow-up terminal calls.";
        }
        for (Pattern pattern : LONG_LIVED_FOREGROUND_PATTERNS) {
            if (pattern.matcher(unquoted).find()) {
                return "This foreground command appears to start a long-lived server/watch process. "
                    + "Run it with background=true, verify readiness (health endpoint/log signal), "
                    + "then execute tests in a separate command.";
            }
        }
        return null;
    }

    private static boolean looksLikeHelpOrVersionCommand(String command) {
        String normalized = " " + (command == null ? "" : command.toLowerCase().replaceAll("\\s+", " ").trim());
        return normalized.contains(" --help")
            || normalized.endsWith(" -h")
            || normalized.contains(" --version")
            || normalized.endsWith(" -v");
    }

    private static String stripQuotedContent(String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(command.length());
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (quote == '"' && ch == '\\' && !escaped) {
                    escaped = true;
                    continue;
                }
                if (ch == quote && !escaped) {
                    quote = 0;
                    result.append("_QUOTE_");
                }
                escaped = false;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                escaped = false;
                continue;
            }
            result.append(ch);
        }
        if (quote != 0) {
            result.append("_QUOTE_");
        }
        return result.toString();
    }

    private boolean hasForegroundNotificationModifier(TerminalArgs args) {
        if (args.notifyOnComplete()) {
            return true;
        }
        if (args.watchPatterns() != null && !args.watchPatterns().isEmpty()) {
            return true;
        }
        Object notify = args.notifyValue();
        if (notify instanceof Boolean b) {
            return b;
        }
        if (notify instanceof List<?> list) {
            return !list.isEmpty();
        }
        return notify != null;
    }

    private NotificationSettings resolveNotifications(TerminalArgs args) {
        boolean notifyOnComplete = args.notifyOnComplete();
        List<String> watchPatterns = args.watchPatterns();
        Object notify = args.notifyValue();
        if (notify == null) {
            return new NotificationSettings(notifyOnComplete, watchPatterns);
        }
        if (notify instanceof Boolean b) {
            return new NotificationSettings(b, List.of());
        }
        if (notify instanceof List<?> list) {
            List<String> patterns = list.stream()
                .map(item -> item == null ? "" : item.toString())
                .filter(item -> !item.isBlank())
                .toList();
            return new NotificationSettings(false, patterns);
        }
        throw new IllegalArgumentException(
            "notify must be true/false (notify on exit) or a list of strings (notify on output pattern match).");
    }

    private ToolResult runCommand(String command, int timeoutSeconds, UUID sessionId, boolean usePty, String workdir,
                                  boolean recordSessionCwd, CommandGuard guard) {
        Process process = null;
        AtomicBoolean interrupted = new AtomicBoolean(false);
        // Read output concurrently with waitFor() to avoid losing buffered data
        // when the PTY closes the stream before all output is flushed (Finding 1.4).
        StringBuilder outputBuffer = new StringBuilder();
        Thread outputReader = null;
        try {
            ProcessBuilder pb;
            String shellScript = null;
            // Hermes parity (terminal_tool.py:3559): track session cwd by
            // appending a marker + pwd after the command. The marker lets us
            // extract the post-command cwd and persist it for the next call.
            // Also capture exported env vars by printing them after the command.
            // Only for foreground non-PTY commands (PTY output is messy).
            String cwdMarker = null;
            if (usePty) {
                pb = new ProcessBuilder("script", "-qec", command, "/dev/null");
            } else {
                if (sessionId != null) {
                    cwdMarker = "\n__CWD_MARKER__:";
                    // Capture both cwd and exported env vars after the command runs.
                    // env_marker uses a unique format unlikely to appear in command output.
                    String envCapture = "\nJAVA_AGENT_EXIT_CODE=$?; printf '" + cwdMarker
                        + "'; pwd; printf '\\n__ENV_MARKER__\\n'; env -0; exit $JAVA_AGENT_EXIT_CODE";
                    shellScript = shellExportPrologue(SESSION_ENV.get(sessionId)) + command + envCapture;
                } else {
                    shellScript = command;
                }
                pb = new ProcessBuilder(ShellExecutableResolver.bash(), "-s");
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

            // Hermes parity: apply previously exported env vars to this process.
            if (sessionId != null) {
                java.util.Map<String, String> savedEnv = SESSION_ENV.get(sessionId);
                if (savedEnv != null && !savedEnv.isEmpty()) {
                    pb.environment().putAll(savedEnv);
                }
            }

            process = pb.start();

            if (shellScript != null) {
                try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(shellScript);
                    writer.write('\n');
                }
            }

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
                return jsonFail("Interrupted by user");
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
                return jsonFail("Command timed out after " + timeoutSeconds + " seconds" + enhanced);
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
            int exitCode = process.exitValue();

            // Hermes parity (terminal_tool.py:3559): extract post-command cwd
            // and exported env vars from markers and persist for the next call.
            if (cwdMarker != null) {
                int markerIdx = output.indexOf("__CWD_MARKER__:");
                if (markerIdx >= 0) {
                    String afterMarker = output.substring(markerIdx + "__CWD_MARKER__:".length());
                    // cwd is the first line after the marker
                    int newlineIdx = afterMarker.indexOf('\n');
                    String postCwd = newlineIdx >= 0
                        ? afterMarker.substring(0, newlineIdx).trim()
                        : afterMarker.trim();
                    // Check for env marker
                    int envMarkerIdx = output.indexOf("__ENV_MARKER__");
                    if (envMarkerIdx >= 0) {
                        // Everything after __ENV_MARKER__\n is env -0 output
                        String envBlob = output.substring(envMarkerIdx + "__ENV_MARKER__\n".length());
                        parseAndStoreEnv(sessionId, envBlob);
                        // Remove env marker section from visible output
                        output = output.substring(0, envMarkerIdx).trim();
                    } else {
                        // Remove cwd marker from visible output
                        output = output.substring(0, markerIdx).trim();
                    }
                    // Also remove the cwd marker line if env marker was found after it
                    if (envMarkerIdx >= 0 && markerIdx < envMarkerIdx) {
                        output = output.substring(0, markerIdx).trim();
                    }
                    if (!postCwd.isEmpty() && new java.io.File(postCwd).isDirectory()) {
                        if (recordSessionCwd) {
                            SESSION_CWD.put(sessionId, postCwd);
                        }
                        actualCwd = postCwd;
                    }
                }
            }

            String redactedOutput = redact(output);

            // Finding 1.3: Call notifyPostExecution after process completes
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
            return jsonFail("Interrupted by user");
        } catch (Exception e) {
            if (interruptToken != null && sessionId != null) {
                interruptToken.unregister(sessionId);
            }
            return jsonFail("Failed to execute command: " + e.getMessage());
        }
    }

    private static ToolResult jsonFail(String error) {
        String message = error == null || error.isBlank() ? "Terminal failed" : error;
        ObjectNode response = JSON.createObjectNode();
        response.put("success", false);
        response.put("error", message);
        return new ToolResult(false, response.toString(), message);
    }

    private String redact(String output) {
        return redactor.redact(output);
    }

    /**
     * Hermes parity: parse {@code env -0} output (NUL-separated KEY=VALUE entries)
     * and store them for the next terminal call. Only stores non-system env vars
     * that are likely user-set (exported in the shell session).
     */
    private void parseAndStoreEnv(UUID sessionId, String envBlob) {
        if (envBlob == null || envBlob.isEmpty()) return;
        // env -0 produces NUL-separated KEY=VALUE entries
        String[] entries = envBlob.split("\0");
        java.util.Map<String, String> envMap = new java.util.HashMap<>();
        for (String entry : entries) {
            int eqIdx = entry.indexOf('=');
            if (eqIdx > 0) {
                String key = entry.substring(0, eqIdx);
                String value = entry.substring(eqIdx + 1);
                // Skip internal/system vars that shouldn't be persisted
                if (!key.startsWith("_") && !key.equals("PWD")
                    && !key.equals("SHLVL") && !key.equals("_")) {
                    envMap.put(key, value);
                }
            }
        }
        if (!envMap.isEmpty()) {
            SESSION_ENV.put(sessionId, java.util.Map.copyOf(envMap));
        }
    }

    private static String shellExportPrologue(java.util.Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return "";
        }
        StringBuilder prologue = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            prologue.append("export ")
                .append(key)
                .append('=')
                .append(shellQuote(entry.getValue()))
                .append('\n');
        }
        return prologue.toString();
    }

    private static String shellQuote(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
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
            while (mp.isAlive() && (System.currentTimeMillis() - startTime) < maxDuration) {
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

    private record NotificationSettings(boolean notifyOnComplete, List<String> watchPatterns) {
    }

    record TerminalArgs(
        @ToolParam(description = "The command to execute on the VM") String command,
        @ToolParam(description = "Max seconds to wait (default 180, foreground max 600).", required = false) Integer timeout,
        @ToolParam(description = "Run in the background, returning a session_id.", required = false) boolean background,
        @ToolParam(description = "With background=true: run in pseudo-terminal for interactive CLIs.", required = false) boolean pty,
        @ToolParam(description = "Working directory for this command.", required = false) String workdir,
        @JsonProperty("notify") @ToolParam(description = "With background=true: true notifies on exit, or a list of strings notifies on output pattern match.", required = false) Object notifyValue,
        @JsonProperty("notify_on_complete") @JsonAlias("notify-on-complete") @ToolParam(description = "Get notified when the process exits.", required = false) boolean notifyOnComplete,
        @JsonProperty("watch_patterns") @JsonAlias("watch-patterns") @ToolParam(description = "Strings to watch for in background output.", required = false) List<String> watchPatterns) {
        TerminalArgs {
            if (command == null) command = "";
            if (watchPatterns == null) watchPatterns = List.of();
        }
    }
}
