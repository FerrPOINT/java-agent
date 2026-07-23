package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
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
import java.util.concurrent.TimeUnit;

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
     */
    public ManagedProcess spawn(String command, int timeoutSeconds) throws IOException {
        String id = "proc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ManagedProcess managed = new ManagedProcess(id, command, process, timeoutSeconds);
        processes.put(id, managed);
        return managed;
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
        @ToolParam(description = "process session_id for non-list actions") String sessionId,
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
        private final List<String> outputBuffer = new ArrayList<>();
        private final Thread readerThread;

        ManagedProcess(String id, String command, Process process, int timeoutSeconds) {
            this.id = id;
            this.command = command;
            this.process = process;
            this.pid = process.pid();
            this.startedAt = Instant.now();
            this.readerThread = new Thread(this::readOutput, "process-reader-" + id);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (outputBuffer) {
                        outputBuffer.add(line);
                        // rolling buffer: keep last 2000 lines
                        if (outputBuffer.size() > 2000) {
                            outputBuffer.remove(0);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        String getOutput() {
            synchronized (outputBuffer) {
                return String.join("\n", outputBuffer);
            }
        }

        String getRecentOutput(int maxLines) {
            synchronized (outputBuffer) {
                int start = Math.max(0, outputBuffer.size() - maxLines);
                return String.join("\n", outputBuffer.subList(start, outputBuffer.size()));
            }
        }

        List<String> getOutputLines() {
            synchronized (outputBuffer) {
                return new ArrayList<>(outputBuffer);
            }
        }

        void writeStdin(String data) {
            try {
                OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(data);
                writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write to stdin: " + e.getMessage(), e);
            }
        }

        void closeStdin() {
            try {
                process.getOutputStream().close();
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
        }
    }
}
