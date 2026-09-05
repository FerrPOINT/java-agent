package com.azhukov.agent.tools.terminal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@AgentTool(
    name = "process",
    description = "Manage background processes started with terminal(background=true). Actions: 'list' (show all), 'poll' (check status + new output), 'log' (full output with pagination), 'wait' (block until done or timeout), 'kill' (terminate), 'write' (send raw stdin data without newline), 'submit' (send data + Enter, for answering prompts), 'close' (close stdin/send EOF).",
    toolset = "terminal"
)
@Component
public class ProcessTool implements ToolHandler {

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    // rev-113 Hermes parity (process_registry.py:3236 _redact_process_result):
    // process output mirrors the terminal redaction — secrets in env dumps,
    // tokens in command output etc. must be masked before the result reaches
    // conversation history. TerminalTool redacts its own output; background
    // process results were passing through verbatim.
    private final com.azhukov.agent.core.security.Redactor redactor;

    /** PR-3 parity ctor. */
    public ProcessTool(com.azhukov.agent.config.AgentProperties properties,
                       com.azhukov.agent.core.security.Redactor redactor) {
        this(redactor);
    }

    public ProcessTool(com.azhukov.agent.core.security.Redactor redactor) {
        this.redactor = redactor;
    }

    /** Test-only: no-op redactor (keeps direct-instantiation tests simple). */
    ProcessTool() {
        this.redactor = new com.azhukov.agent.core.security.Redactor() {
            @Override public String redact(String output) { return output; }
            @Override public String redactEnvVars(String output) { return output; }
        };
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ProcessArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ProcessArgs.class);
        } catch (IllegalArgumentException e) {
            return failJson("Invalid tool arguments: " + e.getMessage());
        }
        String action = args.action() == null ? "" : args.action().toLowerCase();
        String sessionId = args.sessionId() == null ? "" : args.sessionId();

            // PR-3 parity: actions that target a process must carry session_id.
        boolean targetsProcess = args.action() != null
                && java.util.Set.of("poll", "log", "wait", "kill", "write", "submit", "close")
                    .contains(args.action());
        if (targetsProcess && (sessionId == null || sessionId.isBlank())) {
            return failJson("session_id is required for " + args.action());
        }
                return switch (action) {
            case "list" -> listProcesses();
            case "poll" -> poll(sessionId);
            case "log" -> log(sessionId, args.offset(), args.limit());
            case "wait" -> waitFor(sessionId, args.timeout());
            case "kill" -> kill(sessionId);
            case "write" -> writeStdin(sessionId, args.data());
            case "submit" -> submitStdin(sessionId, args.data());
            case "close" -> closeStdin(sessionId);
            default -> failJson("Unknown process action: " + args.action() + ". Use: list, poll, log, wait, kill, write, submit, close");
        };
    }

    /**
     * Spawn a tracked background process. Called by TerminalTool when background=true.
     *
     * @param command       the shell command to run
     * @param timeoutSeconds the timeout in seconds (informational; enforcement is up to the caller)
     * @param usePty        when true, allocate a pseudo-terminal via {@code script -qec} (Finding 1.6)
     * @param notifyOnExit  optional callback fired when the process exits; receives the process id (Finding 1.2)
     * @param workdir       working directory for the process, or null for the current directory
     */
    public ManagedProcess spawn(String command, int timeoutSeconds, boolean usePty,
                                Consumer<String> notifyOnExit, String workdir) throws IOException {
        return spawn(command, timeoutSeconds, usePty, notifyOnExit, workdir, null, null, List.of());
    }

    public ManagedProcess spawn(String command, int timeoutSeconds, boolean usePty,
                                Consumer<String> notifyOnExit, String workdir,
                                Map<String, String> envVars) throws IOException {
        return spawn(command, timeoutSeconds, usePty, notifyOnExit, workdir, envVars, null, List.of());
    }

    public ManagedProcess spawn(String command, int timeoutSeconds, boolean usePty,
                                Consumer<String> notifyOnExit, String workdir,
                                Map<String, String> envVars, UUID ownerSessionId) throws IOException {
        return spawn(command, timeoutSeconds, usePty, notifyOnExit, workdir, envVars, ownerSessionId, List.of());
    }

    public ManagedProcess spawn(String command, int timeoutSeconds, boolean usePty,
                                Consumer<String> notifyOnExit, String workdir,
                                Map<String, String> envVars, UUID ownerSessionId,
                                List<String> watchPatterns) throws IOException {
        String id = "proc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProcessBuilder pb;
        if (usePty) {
            // Finding 1.6: PTY mode for background processes (interactive tools)
            pb = new ProcessBuilder("script", "-qec", command, "/dev/null");
        } else {
            pb = new ProcessBuilder(ShellExecutableResolver.bash(), "-c", command);
        }
        // BUG 4: Set working directory if provided (was previously ignored for background processes)
        if (workdir != null && !workdir.isBlank()) {
            java.io.File dir = new java.io.File(workdir);
            if (!dir.exists()) {
                throw new IOException("Working directory does not exist: " + workdir);
            }
            if (!dir.isDirectory()) {
                throw new IOException("Working directory path is not a directory: " + workdir);
            }
            pb.directory(dir.getAbsoluteFile());
        }
        if (envVars != null && !envVars.isEmpty()) {
            pb.environment().putAll(envVars);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String cwd = pb.directory() == null
            ? Path.of("").toAbsolutePath().normalize().toString()
            : pb.directory().toPath().toAbsolutePath().normalize().toString();
        ManagedProcess managed = new ManagedProcess(
            id, command, process, timeoutSeconds, notifyOnExit, ownerSessionId,
            cwd, notifyOnExit != null, watchPatterns);
        processes.put(id, managed);
        return managed;
    }


    /**
     * Spawn a tracked background process without PTY, notification callback, or workdir.
     * Equivalent to {@code spawn(command, timeoutSeconds, false, null, null)}.
     */
    public ManagedProcess spawn(String command, int timeoutSeconds) throws IOException {
        return spawn(command, timeoutSeconds, false, null, null);
    }

    private ToolResult listProcesses() {
        List<String> lines = new ArrayList<>();
        for (ManagedProcess p : processes.values()) {
            String status = p.process.isAlive() ? "running" : "exited";
            // rev-113: command line redacted too (Hermes redact_sensitive_text
            // on s.command, process_registry.py:2748) — env vars with secrets
            // often ride the command line.
            lines.add(String.format("%s | %s | pid=%s | %s", p.id, status, p.pid, redactor.redact(p.command)));
        }
        return ToolResult.ok(String.join("\n", lines));
    }

    /**
     * h51: Look up a process by full session ID or a unique ID prefix.
     * E.g. "abc123" matches session "abc12345-...". Returns the matching
     * ManagedProcess, or null if no match is found or the prefix is ambiguous.
     */
    private ManagedProcess findProcess(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // Fast path: exact match
        ManagedProcess exact = processes.get(sessionId);
        if (exact != null) {
            return exact;
        }
        // Prefix match: find all processes whose ID starts with the given prefix
        List<ManagedProcess> matches = new ArrayList<>();
        for (Map.Entry<String, ManagedProcess> entry : processes.entrySet()) {
            if (entry.getKey().startsWith(sessionId)) {
                matches.add(entry.getValue());
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return null; // no match or ambiguous
    }

    private ToolResult poll(String sessionId) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        return ToolResult.ok(formatResult(p, false));
    }

    private ToolResult log(@JsonProperty("session_id") @JsonAlias("sessionId") String sessionId, int offset, int limit) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        List<String> lines = p.getOutputLines();
        int start = Math.max(0, offset);
        // BUG 5a: Guard against IndexOutOfBoundsException when offset >= lines.size()
        if (start >= lines.size()) {
            return ToolResult.ok("");
        }
        int end = Math.min(lines.size(), start + (limit > 0 ? limit : 200));
        // rev-113: log output redacted (Hermes _redact_process_result).
        return ToolResult.ok(redactor.redact(String.join("\n", lines.subList(start, end))));
    }

    private ToolResult waitFor(String sessionId, Integer timeout) {
        if (timeout != null && timeout <= 0) {
            return failJson("timeout must be positive (got " + timeout + ")");
        }
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        int waitSeconds = timeout != null && timeout > 0 ? timeout : 1800;
        try {
            boolean finished = p.process.waitFor(waitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                return ToolResult.ok(formatResult(p, true) + "\n(wait timed out, process still running)");
            }
            return ToolResult.ok(formatResult(p, true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Wait interrupted");
        }
    }

    private ToolResult kill(String sessionId) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        processes.remove(p.id);
        p.destroy();
        return ToolResult.ok("Killed process " + p.id);
    }

    private ToolResult writeStdin(String sessionId, String data) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        p.writeStdin(data == null ? "" : data);
        return ToolResult.ok("Data written to stdin");
    }

    private ToolResult submitStdin(String sessionId, String data) {
        return writeStdin(sessionId, (data == null ? "" : data) + "\n");
    }

    private ToolResult closeStdin(String sessionId) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            // PR-3 parity: unknown id is a soft, queryable state — the model can
            // discover it without a hard error (Hermes process_tool not_found).
            try {
                return ToolResult.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("status", "not_found", "error", "No process with ID " + sessionId)));
            } catch (Exception e) {
                return ToolResult.fail("Process not found: " + sessionId);
            }
        }
        p.closeStdin();
        return ToolResult.ok("Stdin closed");
    }

    private String formatResult(ManagedProcess p, boolean fullOutput) {
        boolean alive = p.process.isAlive();
        int exitCode = alive ? -1 : p.process.exitValue();
        String output = fullOutput ? p.getOutput() : p.getRecentOutput(1000);
        // rev-113: output redacted (Hermes _redact_process_result) — poll/wait
        // results reach conversation history verbatim otherwise.
        return String.format(
            "session_id: %s\npid: %s\nstatus: %s\nexit_code: %s\nuptime_seconds: %d\noutput:\n%s",
            p.id, p.pid, alive ? "running" : "exited", exitCode,
            java.time.Duration.between(p.startedAt, Instant.now()).getSeconds(),
            redactor.redact(AnsiStrip.strip(output))
        );
    }

    @PreDestroy
    public void cleanup() {
        for (ManagedProcess p : processes.values()) {
            p.destroy();
        }
        processes.clear();
    }

    public record ProcessArgs(
        @ToolParam(description = "list, poll, log, wait, kill, write, submit, close") String action,
        @ToolParam(description = "process session_id for non-list actions")
        @JsonProperty("session_id") @JsonAlias("sessionId") String sessionId,
        @ToolParam(description = "timeout in seconds for wait", required = false) Integer timeout,
        @ToolParam(description = "offset for log pagination", required = false) int offset,
        @ToolParam(description = "max lines for log pagination", required = false) int limit,
        @ToolParam(description = "data to send to stdin for write/submit", required = false) String data
    ) {}

    public static class ManagedProcess {
        final String id;
        final String command;
        final Process process;
        final long pid;
        final Instant startedAt;
        // Finding 1.5: ConcurrentLinkedDeque is thread-safe and avoids O(n) remove(0) shifts.
        private final ConcurrentLinkedDeque<String> outputBuffer = new ConcurrentLinkedDeque<>();
        // BUG 5b: AtomicInteger for O(1) size checks instead of ConcurrentLinkedDeque.size() which is O(n)
        private final AtomicInteger lineCount = new AtomicInteger(0);
        private final Thread readerThread;
        private final Thread exitWatcherThread;
        private OutputStreamWriter stdinWriter;
        // Finding 1.2: optional callback fired when the process exits
        private final Consumer<String> notifyOnExit;
        /** PR-3 parity: session that owns this process (kill-on-session-end). */
        private UUID ownerSessionId;
        private final String cwd;
        private final boolean notifyOnComplete;
        private final java.util.List<String> watchPatterns;
        /** PR-3 parity: who/what terminated this process (observability). */
        private volatile String terminationSource = "";
        String terminationSource() { return terminationSource; }
        // Rolling buffer limit
        private static final int MAX_LINES = 2000;

        ManagedProcess(String id, String command, Process process, int timeoutSeconds) {
            this(id, command, process, timeoutSeconds, null, null);
        }

        ManagedProcess(String id, String command, Process process, int timeoutSeconds,
                       Consumer<String> notifyOnExit) {
            this(id, command, process, timeoutSeconds, notifyOnExit, null);
        }

        ManagedProcess(String id, String command, Process process, int timeoutSeconds,
                       Consumer<String> notifyOnExit, UUID ownerSessionId) {
            this(id, command, process, timeoutSeconds, notifyOnExit, ownerSessionId,
                Path.of("").toAbsolutePath().normalize().toString(), notifyOnExit != null, List.of());
        }

        ManagedProcess(String id, String command, Process process, int timeoutSeconds,
                       Consumer<String> notifyOnExit, UUID ownerSessionId,
                       String cwd, boolean notifyOnComplete, List<String> watchPatterns) {
            this.id = id;
            this.command = command;
            this.process = process;
            this.ownerSessionId = ownerSessionId;
            this.cwd = cwd;
            this.notifyOnComplete = notifyOnComplete;
            this.watchPatterns = watchPatterns == null ? List.of() : List.copyOf(watchPatterns);
            this.pid = process.pid();
            this.startedAt = Instant.now();
            this.notifyOnExit = notifyOnExit;
            this.readerThread = new Thread(this::readOutput, "process-reader-" + id);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
            // Finding 1.2: watch for process exit and fire the notification callback
            this.exitWatcherThread = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Wait briefly for the reader thread to drain remaining output
                try {
                    readerThread.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (notifyOnExit != null) {
                    try {
                        notifyOnExit.accept(id);
                    } catch (Exception e) {
                        // Callback failures must not propagate into the watcher thread
                    }
                }
            }, "process-exit-watcher-" + id);
            this.exitWatcherThread.setDaemon(true);
            this.exitWatcherThread.start();
        }


        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuffer.addLast(line);
                    // M11 fix: single atomic step — append and prune keep lineCount and the
                    // deque consistent under concurrent getRecentOutput() readers.
                    if (lineCount.incrementAndGet() > MAX_LINES) {
                        if (outputBuffer.pollFirst() != null) {
                            lineCount.decrementAndGet();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        String getOutput() {
            return String.join("\n", outputBuffer);
        }

        String getRecentOutput(int maxLines) {
            // M11 fix: derive the slice from the deque itself, not from a separately
            // tracked counter — the two can transiently diverge under concurrent appends.
            List<String> snapshot = new ArrayList<>(outputBuffer);
            int start = Math.max(0, snapshot.size() - maxLines);
            return String.join("\n", snapshot.subList(start, snapshot.size()));
        }

        List<String> getOutputLines() {
            return new ArrayList<>(outputBuffer);
        }

        void writeStdin(String data) {
            try {
                // Reuse a single OutputStreamWriter across calls instead of creating a new
                // one each time. A new writer wraps the same underlying OutputStream but is
                // never explicitly flushed/closed by the previous call, potentially leaving
                // buffered data unwritten. Reusing one writer ensures all data is flushed.
                if (stdinWriter == null) {
                    stdinWriter = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                }
                stdinWriter.write(data);
                stdinWriter.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write to stdin: " + e.getMessage(), e);
            }
        }

        void closeStdin() {
            try {
                if (stdinWriter != null) {
                    stdinWriter.flush();
                    stdinWriter.close();
                    stdinWriter = null;
                } else {
                    process.getOutputStream().close();
                }
            } catch (IOException ignored) {
            }
        }

        void destroy() {
            destroy("process.kill");
        }

        void destroy(String source) {
            terminationSource = source == null || source.isBlank() ? "process.kill" : source;
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exitWatcherThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void awaitOutputDrain(long millis) {
            try {
                readerThread.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    /** PR-3 parity: bind a spawned process to the session that owns it. */
    public void claimProcess(ManagedProcess p, UUID ownerSessionId) {
        if (p != null) p.ownerSessionId = ownerSessionId;
    }

    /** PR-3 parity: kill every live process owned by the session (run.stop). */
    public int killOwnedBy(UUID ownerSessionId) {
        if (ownerSessionId == null) {
            return 0;
        }
        int killed = 0;
        for (ManagedProcess p : processes.values()) {
            if (ownerSessionId.equals(p.ownerSessionId) && p.process.isAlive()) {
                p.destroy("api_server_run_stop");
                killed++;
            }
        }
        return killed;
    }

    /** PR-3 parity: structured JSON failures for invalid process-tool input. */
    private static ToolResult failJson(String error) {
        String message = error == null || error.isBlank() ? "Process tool failed" : error;
        try {
            return new ToolResult(false,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("error", message)),
                message);
        } catch (Exception e) {
            return ToolResult.fail(message);
        }
    }

}
