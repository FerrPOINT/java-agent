package com.azhukov.agent.tools.code;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
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
        int timeout = args.timeout() > 0 ? args.timeout() : 30;
        return runPython(args.code(), timeout);
    }

    private ToolResult runPython(String code, int timeoutSeconds) {
        try {
            Path tempFile = Files.createTempFile("agent_code_", ".py");
            Files.writeString(tempFile, code, StandardCharsets.UTF_8);
            tempFile.toFile().deleteOnExit();

            ProcessBuilder pb = new ProcessBuilder("python3", tempFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("Code execution timed out after " + timeoutSeconds + " seconds");
            }
            String output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(java.util.stream.Collectors.joining("\n"));
            return ToolResult.ok(output);
        } catch (Exception e) {
            return ToolResult.fail("Failed to execute code: " + e.getMessage());
        }
    }

    public record ExecuteCodeArgs(
        @ToolParam(description = "Python code to execute") String code,
        @ToolParam(description = "timeout in seconds", required = false) int timeout
    ) {}
}
