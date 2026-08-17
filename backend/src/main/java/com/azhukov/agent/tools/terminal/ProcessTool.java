package com.azhukov.agent.tools.terminal;

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

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ProcessArgs args = ToolHandler.parseJson(arguments, ProcessArgs.class);
        String action = args.action() == null ? "" : args.action().toLowerCase();
        String sessionId = args.sessionId() == null ? "" : args.sessionId();

        return switch (action) {
            case "list" -> listProcesses();
            case "poll" -> poll(sessionId);
            case "log" -> log(sessionId, args.offset(), args.limit());
            case "wait" -> waitFor(sessionId, args.timeout());
            case "kill" -> kill(sessionId);
            case "write" -> writeStdin(sessionId, args.data());
            case "submit" -> submitStdin(sessionId, args.data());
            case "close" -> closeStdin(sessionId);
            default -> ToolResult.fail("Unknown process action: " + args.action() + ". Use: list, poll, log, wait, kill, write, submit, close");
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
        String id = "proc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProcessBuilder pb;
        if (usePty) {
            // Finding 1.6: PTY mode for background processes (interactive tools)
            pb = new ProcessBuilder("script", "-qec", command, "/dev/null");
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
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
            pb.directory(dir);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ManagedProcess managed = new ManagedProcess(id, command, process, timeoutSeconds, notifyOnExit);
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
            lines.add(String.format("%s | %s | pid=%s | %s", p.id, status, p.pid, p.command));
        }
        return ToolResult.ok(String.join("\n", lines));
    }

    private ToolResult poll(String sessionId) {
        ManagedProcess p = processes.get(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        return ToolResult.ok(formatResult(p, false));
    }

    private ToolResult log(String sessionId, int offset, int limit) {
        ManagedProcess p = processes.get(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        List<String> lines = p.getOutputLines();
        int start = Math.max(0, offset);
        // BUG 5a: Guard against IndexOutOfBoundsException when offset >= lines.size()
        if (start >= lines.size()) {
            return ToolResult.ok("");
        }
        int end = Math.min(lines.size(), start + (limit > 0 ? limit : 200));
        return ToolResult.ok(String.join("\n", lines.subList(start, end)));
    }

    private ToolResult waitFor(String sessionId, int timeout) {
        ManagedProcess p = processes.get(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        int waitSeconds = timeout > 0 ? timeout : 1800;
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
        ManagedProcess p = processes.remove(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        p.destroy();
        return ToolResult.ok("Killed process " + sessionId);
    }

    private ToolResult writeStdin(String sessionId, String data) {
        ManagedProcess p = processes.get(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        p.writeStdin(data == null ? "" : data);
        return ToolResult.ok("Data written to stdin");
    }

    private ToolResult submitStdin(String sessionId, String data) {
        return writeStdin(sessionId, (data == null ? "" : data) + "\n");
    }

    private ToolResult closeStdin(String sessionId) {
        ManagedProcess p = processes.get(sessionId);
        if (p == null) {
            return ToolResult.fail("Process not found: " + sessionId);
        }
        p.closeStdin();
        return ToolResult.ok("Stdin closed");
    }

    private String formatResult(ManagedProcess p, boolean fullOutput) {
        boolean alive = p.process.isAlive();
        int exitCode = alive ? -1 : p.process.exitValue();
        String output = fullOutput ? p.getOutput() : p.getRecentOutput(1000);
        return String.format(
            "session_id: %s\npid: %s\nstatus: %s\nexit_code: %s\nuptime_seconds: %d\noutput:\n%s",
            p.id, p.pid, alive ? "running" : "exited", exitCode,
            java.time.Duration.between(p.startedAt, Instant.now()).getSeconds(),
            output
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
        @ToolParam(description = "process session_id for non-list actions") @JsonAlias("session_id") String sessionId,
        @ToolParam(description = "timeout in seconds for wait", required = false) int timeout,
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
        // Rolling buffer limit
        private static final int MAX_LINES = 2000;

        ManagedProcess(String id, String command, Process process, int timeoutSeconds) {
            this(id, command, process, timeoutSeconds, null);
        }

        ManagedProcess(String id, String command, Process process, int timeoutSeconds,
                       Consumer<String> notifyOnExit) {
            this.id = id;
            this.command = command;
            this.process = process;
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
                    lineCount.incrementAndGet();
                    // Rolling buffer: prune oldest entries when exceeding the limit.
                    // ConcurrentLinkedDeque peek/remove are O(1) — no array shifts.
                    // BUG 5b: Use AtomicInteger lineCount instead of O(n) outputBuffer.size()
                    while (lineCount.get() > MAX_LINES) {
                        if (outputBuffer.pollFirst() != null) {
                            lineCount.decrementAndGet();
                        } else {
                            break;
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
            // BUG 5b: Use AtomicInteger lineCount instead of O(n) outputBuffer.size()
            int size = lineCount.get();
            int start = Math.max(0, size - maxLines);
            List<String> recent = new ArrayList<>();
            int i = 0;
            for (String line : outputBuffer) {
                if (i >= start) {
                    recent.add(line);
                }
                i++;
            }
            return String.join("\n", recent);
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
    }
}
