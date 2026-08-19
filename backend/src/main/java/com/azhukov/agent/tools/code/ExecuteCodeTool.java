package com.azhukov.agent.tools.code;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
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
    description = "Execute a Python code snippet in a temporary file and return stdout/stderr.",
    toolset = "coding"
)
@Component
public class ExecuteCodeTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExecuteCodeArgs args = ToolHandler.parseJson(arguments, ExecuteCodeArgs.class);
        if (args.code() == null || args.code().isBlank()) {
            return ToolResult.fail("Code is required");
        }
        int timeout = 300;
        if (args.timeout() != null && !args.timeout().isBlank()) {
            try {
                timeout = Integer.parseInt(args.timeout().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return runPython(args.code(), timeout);
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
            // waiting for the process. Reading only after waitFor deadlocks when the
            // child fills the OS pipe buffer (~64KB) and blocks on write() forever.
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
        @ToolParam(description = "Python code to execute")
        private String code;
        @ToolParam(description = "timeout in seconds", required = false)
        private String timeout;

        public String code() { return code; }
        public String timeout() { return timeout; }
    }
}
