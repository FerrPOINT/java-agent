package com.azhukov.agent.tools.code;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@AgentTool(
    name = "execute_code",
    description = "Run a standalone Python script for local computation and data transformation. Use when loops, filtering, parsing, or calculations are more efficient than several manual tool calls. This runtime does NOT provide the Hermes `hermes_tools` Python module — do not import it or expect scripts to call agent tools programmatically. Use normal tools directly for web/file/terminal operations.\n\nThe script runs with Python 3, standard library, and the process working directory. Print the final result to stdout. Execution is capped at 5 minutes. For a single shell command, use terminal instead.",
    toolset = "coding"
)
@Component
@RequiredArgsConstructor
public class ExecuteCodeTool implements ToolHandler {
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_STDOUT_BYTES = 50_000;

    private final Redactor redactor;

    // Kept for direct unit construction; Spring uses the required constructor.
    ExecuteCodeTool() {
        this(new NoopRedactor());
    }

    /** No-op redactor for unit tests that construct the tool directly. */
    private static class NoopRedactor implements Redactor {
        @Override public String redact(String output) { return output; }
        @Override public String redactEnvVars(String output) { return output; }
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExecuteCodeArgs args = ToolHandler.parseJson(arguments, ExecuteCodeArgs.class);
        if (args.code() == null || args.code().isBlank()) {
            // Hermes parity: check if 'command' was sent instead of 'code'
            try {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
                if (tree.has("command") && !tree.get("command").isNull() && !tree.get("command").asText().isBlank()) {
                    return ToolResult.fail(
                        "execute_code received a 'command' parameter, but it requires " +
                        "Python source in 'code'. Use terminal(command=...) for shell " +
                        "commands; for Python, retry as execute_code(code=...).");
                }
            } catch (Exception ignored) { }
            return ToolResult.fail(
                "execute_code requires Python source in 'code'. " +
                "Use terminal(command=...) for shell commands; " +
                "for Python, retry as execute_code(code=...).");
        }
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (args.timeout() != null && !args.timeout().isBlank()) {
            try {
                timeout = Integer.parseInt(args.timeout().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return runPython(args.code(), Math.min(timeout, MAX_TIMEOUT_SECONDS));
    }

    private ToolResult runPython(String code, int timeoutSeconds) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("agent_code_", ".py");
            Files.writeString(tempFile, code, StandardCharsets.UTF_8);

            var pb = createProcessBuilder(tempFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Audit C5: read the merged stdout/stderr on a separate thread WHILE
            // waiting for the process. Reading only after waitFor deadlocks when
            // the child fills the OS pipe buffer (~64KB) and blocks on write() forever.
            var outputFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                } catch (IOException e) {
                    return "";
                }
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                return ToolResult.fail("Code execution timed out after " + timeoutSeconds + " seconds");
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            // Hermes parity (code_execution_tool.py:1220-1230): truncate stdout to
            // 50KB head+tail, strip ANSI, and redact secrets before returning.
            output = truncateStdout(output);
            output = redactor.redact(output);

            if (exitCode != 0) {
                // Hermes parity: non-zero exit returns error with output content
                // so the model can see what went wrong.
                String errorMsg = "Script exited with code " + exitCode;
                if (output != null && !output.isBlank()) {
                    return ToolResult.fail(errorMsg + "\n" + output);
                }
                return ToolResult.fail(errorMsg);
            }
            return ToolResult.ok(output);
        } catch (Exception e) {
            return ToolResult.fail("Failed to execute code: " + e.getMessage());
        } finally {
            // Audit L2: delete the temp file eagerly instead of relying on
            // deleteOnExit() which leaks files on kill -9 / crash.
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Hermes parity: 50KB head+tail stdout cap (code_execution_tool.py:76,122-132). */
    private String truncateStdout(String output) {
        if (output == null || output.isEmpty()) {
            return output == null ? "" : output;
        }
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_STDOUT_BYTES) {
            return output;
        }
        int headBytes = MAX_STDOUT_BYTES * 3 / 4; // 75% head
        int tailBytes = MAX_STDOUT_BYTES - headBytes; // 25% tail
        String head = new String(bytes, 0, headBytes, StandardCharsets.UTF_8);
        String tail = new String(bytes, bytes.length - tailBytes, tailBytes, StandardCharsets.UTF_8);
        int omitted = bytes.length - MAX_STDOUT_BYTES;
        return head + "\n[... " + omitted + " bytes omitted ...]\n" + tail;
    }

    interface ProcessBuilderLike {
        ProcessBuilderLike redirectErrorStream(boolean redirectErrorStream);
        Process start() throws IOException;
    }

    ProcessBuilderLike createProcessBuilder(String scriptPath) {
        ProcessBuilder pb = new ProcessBuilder("python3", scriptPath);
        return new ProcessBuilderLike() {
            @Override
            public ProcessBuilderLike redirectErrorStream(boolean redirectErrorStream) {
                pb.redirectErrorStream(redirectErrorStream);
                return this;
            }

            @Override
            public Process start() throws IOException {
                return pb.start();
            }
        };
    }

    public static class ExecuteCodeArgs {
        @ToolParam(description = "Python code to execute. The hermes_tools module is unavailable; use this for local computation only.")
        private String code;
        @ToolParam(description = "timeout in seconds", required = false)
        private String timeout;

        public String code() { return code; }
        public String timeout() { return timeout; }
    }
}
