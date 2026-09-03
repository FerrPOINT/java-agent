package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@AgentTool(
    name = "process",
    description = "Manage background processes started with terminal(background=true). Actions: 'list' (show all), 'poll' (check status + new output), 'log' (full output with pagination), 'wait' (block until done or timeout), 'kill' (terminate), 'write' (send raw stdin data without newline), 'submit' (send data + Enter, for answering prompts), 'close' (close stdin/send EOF).",
    toolset = "terminal"
)
@Component
public class ProcessTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LOG_LIMIT = 200;

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final AgentProperties properties;
    private final Redactor redactor;

    public ProcessTool() {
        this(new AgentProperties(), new NoopRedactor());
    }

    @Autowired
    public ProcessTool(AgentProperties properties, Redactor redactor) {
        this.properties = properties;
        this.redactor = redactor;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ProcessArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ProcessArgs.class);
        } catch (Exception e) {
            return failJson("Invalid tool arguments: " + e.getMessage());
        }
        String action = args.action() == null ? "" : args.action().toLowerCase();
        String sessionId = args.sessionId() == null ? "" : args.sessionId().trim();

        if (requiresSessionId(action) && sessionId.isBlank()) {
            return failJson("session_id is required for " + action);
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
            default -> failJson("Unknown process action: " + args.action()
                + ". Use: list, poll, log, wait, kill, write, submit, close");
        };
    }

    private static boolean requiresSessionId(String action) {
        return switch (action) {
            case "poll", "log", "wait", "kill", "write", "submit", "close" -> true;
            default -> false;
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
        return spawn(command, timeoutSeconds, usePty, notifyOnExit, workdir, null, null);
    }

    public ManagedProcess spawn(String command, int timeoutSeconds, boolean usePty,
                                Consumer<String> notifyOnExit, String workdir,
                                Map<String, String> envVars) throws IOException {
        return spawn(command, timeoutSeconds, usePty, notifyOnExit, workdir, envVars, null);
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
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ManagedProcess p : processes.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("session_id", p.id);
            entry.put("command", truncate(sanitizeCommand(p.command), 200));
            entry.put("cwd", p.cwd);
            entry.put("pid", p.pid);
            entry.put("started_at", p.startedAt.toString());
            entry.put("uptime_seconds", uptimeSeconds(p));
            entry.put("status", p.isAlive() ? "running" : "exited");
            entry.put("output_preview", sanitizeOutput(p.getRecentChars(200)));
            if (p.notifyOnComplete) {
                entry.put("notify_on_complete", true);
            }
            if (!p.watchPatterns.isEmpty()) {
                entry.put("watch_patterns", p.watchPatterns);
                entry.put("watch_hit", false);
            }
            if (!p.isAlive()) {
                entry.put("exit_code", p.exitCode());
                entry.put("completion_reason", p.completionReason);
                entry.put("termination_source", p.terminationSource);
            }
            entries.add(entry);
        }
        return okJson(Map.of("processes", entries));
    }

    /**
     * h51: Look up a process by full session ID or a unique ID prefix.
     * E.g. "proc_abcd" or bare suffix "abcd" matches "proc_abcdef123456".
     * Hermes rejects too-short non-exact prefixes to avoid accidental actions.
     */
    private ManagedProcess findProcess(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String query = sessionId.trim();
        // Fast path: exact match
        ManagedProcess exact = processes.get(query);
        if (exact != null) {
            return exact;
        }
        if (!query.startsWith("proc_")) {
            query = "proc_" + query;
        }
        String suffix = query.substring("proc_".length());
        if (suffix.length() < 4) {
            return null;
        }
        // Prefix match: find all processes whose ID starts with the given prefix
        List<ManagedProcess> matches = new ArrayList<>();
        for (Map.Entry<String, ManagedProcess> entry : processes.entrySet()) {
            if (entry.getKey().startsWith(query)) {
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
            return processNotFound(sessionId);
        }
        return okJson(pollSnapshot(p));
    }

    private ToolResult log(String sessionId, Integer offset, Integer limit) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            return processNotFound(sessionId);
        }
        String fullOutput = sanitizeOutput(p.getOutput());
        List<String> lines = fullOutput.isEmpty() ? List.of() : fullOutput.lines().toList();
        int totalLines = lines.size();
        int effectiveLimit = limit == null ? DEFAULT_LOG_LIMIT : limit;
        List<String> selected;
        if (offset == null && effectiveLimit > 0) {
            int start = Math.max(0, totalLines - effectiveLimit);
            selected = lines.subList(start, totalLines);
        } else {
            int start = Math.max(0, offset == null ? 0 : offset);
            int end = effectiveLimit <= 0 ? start : Math.min(totalLines, start + effectiveLimit);
            if (start >= totalLines || end <= start) {
                selected = List.of();
            } else {
                selected = lines.subList(start, end);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", p.id);
        result.put("command", sanitizeCommand(p.command));
        result.put("status", p.isAlive() ? "running" : "exited");
        result.put("output", String.join("\n", selected));
        result.put("total_lines", totalLines);
        result.put("showing", selected.size() + " lines");
        return okJson(result);
    }

    private ToolResult waitFor(String sessionId, Integer timeout) {
        if (timeout != null && timeout <= 0) {
            return failJson("timeout must be positive (got " + timeout + ")");
        }
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            return processNotFound(sessionId);
        }
        int requested = timeout == null
            ? properties.getTerminal().getDefaultTimeoutSeconds()
            : timeout;
        int max = properties.getTerminal().getMaxTimeoutSeconds();
        int waitSeconds = Math.min(requested, max);
        try {
            boolean finished = p.process.waitFor(waitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "timeout");
                result.put("command", sanitizeCommand(p.command));
                result.put("output", sanitizeOutput(p.getRecentChars(1000)));
                result.put("process_running", true);
                String note = "Wait window of " + waitSeconds
                    + "s elapsed; the process is still running. This is not an error.";
                if (requested > max) {
                    note = "Requested wait of " + requested + "s was clamped to configured limit of "
                        + max + "s. " + note;
                }
                result.put("timeout_note", note);
                return okJson(result);
            }
            p.awaitOutputDrain(3000);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "exited");
            result.put("command", sanitizeCommand(p.command));
            result.put("exit_code", p.exitCode());
            result.put("completion_reason", p.completionReason);
            result.put("termination_source", p.terminationSource);
            result.put("output", sanitizeOutput(p.getRecentChars(2000)));
            if (requested > max) {
                result.put("timeout_note", "Requested wait of " + requested
                    + "s was clamped to configured limit of " + max + "s");
            }
            return okJson(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failJson("Wait interrupted");
        }
    }

    private ToolResult kill(String sessionId) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            return processNotFound(sessionId);
        }
        if (!p.isAlive()) {
            p.awaitOutputDrain(1000);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "already_exited");
            result.put("command", sanitizeCommand(p.command));
            result.put("exit_code", p.exitCode());
            result.put("completion_reason", p.completionReason);
            result.put("termination_source", p.terminationSource);
            result.put("output", sanitizeOutput(p.getRecentChars(2000)));
            return okJson(result);
        }
        p.destroy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "killed");
        result.put("session_id", p.id);
        result.put("completion_reason", p.completionReason);
        result.put("termination_source", p.terminationSource);
        result.put("output", sanitizeOutput(p.getRecentChars(2000)));
        return okJson(result);
    }

    public int killOwnedBy(UUID ownerSessionId) {
        if (ownerSessionId == null) {
            return 0;
        }
        int killed = 0;
        for (ManagedProcess p : processes.values()) {
            if (ownerSessionId.equals(p.ownerSessionId) && p.isAlive()) {
                p.destroy("api_server_run_stop");
                killed++;
            }
        }
        return killed;
    }

    private ToolResult writeStdin(String sessionId, String data) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            return processNotFound(sessionId);
        }
        if (!p.isAlive()) {
            return okJson(Map.of(
                "status", "already_exited",
                "error", "Process has already finished"
            ));
        }
        try {
            p.writeStdin(data == null ? "" : data);
        } catch (IllegalStateException e) {
            return okJson(Map.of(
                "status", "error",
                "error", e.getMessage()
            ));
        }
        return okJson(Map.of(
            "status", "ok",
            "bytes_written", data == null ? 0 : data.length()
        ));
    }

    private ToolResult submitStdin(String sessionId, String data) {
        return writeStdin(sessionId, (data == null ? "" : data) + "\n");
    }

    private ToolResult closeStdin(String sessionId) {
        ManagedProcess p = findProcess(sessionId);
        if (p == null) {
            return processNotFound(sessionId);
        }
        if (!p.isAlive()) {
            return okJson(Map.of(
                "status", "already_exited",
                "error", "Process has already finished"
            ));
        }
        p.closeStdin();
        return okJson(Map.of("status", "ok", "message", "stdin closed"));
    }

    private Map<String, Object> pollSnapshot(ManagedProcess p) {
        boolean alive = p.isAlive();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", p.id);
        result.put("command", sanitizeCommand(p.command));
        result.put("status", alive ? "running" : "exited");
        result.put("pid", p.pid);
        result.put("uptime_seconds", uptimeSeconds(p));
        result.put("output_preview", sanitizeOutput(p.getRecentChars(1000)));
        if (!alive) {
            result.put("exit_code", p.exitCode());
            result.put("completion_reason", p.completionReason);
            result.put("termination_source", p.terminationSource);
        }
        return result;
    }

    private ToolResult okJson(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (IOException e) {
            return failJson("Failed to serialize process result: " + e.getMessage());
        }
    }

    private ToolResult failJson(String error) {
        String message = error == null || error.isBlank() ? "Process tool failed" : error;
        try {
            return new ToolResult(false, MAPPER.writeValueAsString(Map.of("error", message)), message);
        } catch (IOException e) {
            return new ToolResult(false, "{\"error\":\"Process tool failed\"}", message);
        }
    }

    private ToolResult processNotFound(String sessionId) {
        return okJson(Map.of(
            "status", "not_found",
            "error", "No process with ID " + sessionId
        ));
    }

    private String sanitizeCommand(String command) {
        return redactor.redact(command == null ? "" : command);
    }

    private String sanitizeOutput(String output) {
        return redactor.redact(AnsiStrip.strip(output == null ? "" : output));
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static long uptimeSeconds(ManagedProcess p) {
        return java.time.Duration.between(p.startedAt, Instant.now()).getSeconds();
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
        @ToolParam(description = "process session_id for non-list actions. A unique prefix works too, including a bare suffix such as '4dae' for 'proc_4dae56ca81f6'.") @JsonProperty("session_id") @JsonAlias("sessionId") String sessionId,
        @ToolParam(description = "timeout in seconds for wait. Must be positive when supplied.", required = false) Integer timeout,
        @ToolParam(description = "offset for log pagination. Omit to return the last 200 lines.", required = false) Integer offset,
        @ToolParam(description = "max lines for log pagination", required = false) Integer limit,
        @ToolParam(description = "data to send to stdin for write/submit", required = false) String data
    ) {}

    private static class NoopRedactor implements Redactor {
        @Override
        public String redact(String output) {
            return output;
        }

        @Override
        public String redactEnvVars(String output) {
            return output;
        }
    }

    public static class ManagedProcess {
        final String id;
        final String command;
        final Process process;
        final UUID ownerSessionId;
        final String cwd;
        final boolean notifyOnComplete;
        final List<String> watchPatterns;
        final long pid;
        final Instant startedAt;
        private final StringBuilder outputBuffer = new StringBuilder();
        private final Object outputLock = new Object();
        private final Thread readerThread;
        private final Thread exitWatcherThread;
        private OutputStreamWriter stdinWriter;
        // Finding 1.2: optional callback fired when the process exits
        private final Consumer<String> notifyOnExit;
        private volatile String completionReason = "exited";
        private volatile String terminationSource = "";
        private volatile Integer exitCodeOverride;
        private volatile boolean forceExited;
        private static final int MAX_OUTPUT_CHARS = 200_000;

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
            try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    appendOutput(new String(buffer, 0, read));
                }
            } catch (Exception ignored) {
            }
        }

        String getOutput() {
            synchronized (outputLock) {
                return outputBuffer.toString();
            }
        }

        String getRecentOutput(int maxLines) {
            if (maxLines <= 0) {
                return "";
            }
            List<String> lines = getOutputLines();
            int start = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(start, lines.size()));
        }

        List<String> getOutputLines() {
            String output = getOutput();
            if (output.isEmpty()) {
                return List.of();
            }
            return output.lines().toList();
        }

        String getRecentChars(int maxChars) {
            if (maxChars <= 0) {
                return "";
            }
            synchronized (outputLock) {
                int start = Math.max(0, outputBuffer.length() - maxChars);
                return outputBuffer.substring(start);
            }
        }

        private void appendOutput(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            synchronized (outputLock) {
                outputBuffer.append(text);
                if (outputBuffer.length() > MAX_OUTPUT_CHARS) {
                    outputBuffer.delete(0, outputBuffer.length() - MAX_OUTPUT_CHARS);
                }
            }
        }

        void awaitOutputDrain(long millis) {
            try {
                readerThread.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int exitCode() {
            if (isAlive()) {
                return -1;
            }
            if (exitCodeOverride != null) {
                return exitCodeOverride;
            }
            return process.exitValue();
        }

        boolean isAlive() {
            return !forceExited && process.isAlive();
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
            completionReason = "killed";
            terminationSource = source == null || source.isBlank() ? "process.kill" : source;
            exitCodeOverride = -15;
            forceExited = true;
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {
            }
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } catch (Exception e) {
                try {
                    process.destroyForcibly();
                } catch (Exception ignored) {
                }
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
    }
}
