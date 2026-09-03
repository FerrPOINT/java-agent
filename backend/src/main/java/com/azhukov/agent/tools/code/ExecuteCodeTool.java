package com.azhukov.agent.tools.code;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.terminal.AnsiStrip;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@AgentTool(
    name = "execute_code",
    description = "Run a standalone Python script for local computation and data transformation. Use when loops, filtering, parsing, or calculations are more efficient than several manual tool calls. This runtime does NOT provide the Hermes `hermes_tools` Python module — do not import it or expect scripts to call agent tools programmatically. Use normal tools directly for web/file/terminal operations.\n\nSupported execution mode in java-agent is `local` per-call Python. Hermes-only modes such as `session_kernel` and `remote_rpc` are rejected explicitly instead of silently falling back to local execution. The script runs with Python 3, standard library, and the process working directory. Print the final result to stdout. Execution is capped at 5 minutes. For a single shell command, use terminal instead.",
    toolset = "code_execution"
)
@Component
@RequiredArgsConstructor
public class ExecuteCodeTool implements ToolHandler {
    private static final String MODE_LOCAL = "local";
    private static final String MODE_SESSION_KERNEL = "session_kernel";
    private static final String MODE_REMOTE_RPC = "remote_rpc";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_STDOUT_BYTES = 50_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SAFE_ENV_PREFIXES = List.of(
        "PATH", "HOME", "USER", "LANG", "LC_", "TERM", "TMPDIR", "TMP", "TEMP",
        "SHELL", "LOGNAME", "XDG_", "PYTHONPATH", "VIRTUAL_ENV", "CONDA"
    );
    private static final List<String> SECRET_ENV_SUBSTRINGS = List.of(
        "KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH",
        "DSN", "WEBHOOK", "CREDS", "BEARER", "APIKEY"
    );
    private static final Set<String> HERMES_CHILD_ALLOWED_ENV = Set.of(
        "HERMES_HOME",
        "HERMES_PROFILE",
        "HERMES_CONFIG",
        "HERMES_ENV",
        "HERMES_DELEGATED_CHILD_CONTEXT"
    );
    private static final Set<String> WINDOWS_ESSENTIAL_ENV = Set.of(
        "SYSTEMROOT",
        "SYSTEMDRIVE",
        "WINDIR",
        "COMSPEC",
        "PATHEXT",
        "OS",
        "PROCESSOR_ARCHITECTURE",
        "NUMBER_OF_PROCESSORS",
        "PUBLIC",
        "ALLUSERSPROFILE",
        "PROGRAMDATA",
        "PROGRAMFILES",
        "PROGRAMFILES(X86)",
        "PROGRAMW6432",
        "APPDATA",
        "LOCALAPPDATA",
        "USERPROFILE",
        "USERDOMAIN",
        "USERNAME",
        "HOMEDRIVE",
        "HOMEPATH",
        "COMPUTERNAME"
    );

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
        JsonNode rawArgs = parseRawArguments(arguments);
        if (rawArgs != null && rawArgs.has("code") && !rawArgs.get("code").isNull()
            && !rawArgs.get("code").isTextual()) {
            String typeName = jsonTypeName(rawArgs.get("code"));
            return jsonToolError(
                "execute_code received " + articleFor(typeName) + " " + typeName + " in 'code', but it " +
                "requires Python source as a string. Retry as execute_code(code=\"...\").");
        }
        if (rawArgs != null && rawArgs.has("mode") && !rawArgs.get("mode").isNull()
            && !rawArgs.get("mode").isTextual()) {
            String typeName = jsonTypeName(rawArgs.get("mode"));
            return jsonToolError(
                "execute_code received " + articleFor(typeName) + " " + typeName + " in 'mode', but it " +
                "requires an execution mode string. Supported mode in java-agent: local.");
        }
        ExecuteCodeArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ExecuteCodeArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonToolError(e.getMessage());
        }
        if (args.code() == null || args.code().isBlank()) {
            // Hermes parity: check if 'command' was sent instead of 'code'
            if (rawArgs != null && rawArgs.has("command") && !rawArgs.get("command").isNull()
                && !rawArgs.get("command").asText().isBlank()) {
                return jsonToolError(
                    "execute_code received a 'command' parameter, but it requires " +
                    "Python source in 'code'. Use terminal(command=...) for shell " +
                    "commands; for Python, retry as execute_code(code=...).");
            }
            return jsonToolError(
                "No code provided. execute_code requires a non-empty 'code' " +
                "parameter containing Python source. To run shell commands, use " +
                "terminal(command=...) instead.");
        }
        String mode = normalizeExecutionMode(args.mode());
        if (!MODE_LOCAL.equals(mode)) {
            return unsupportedExecutionMode(mode);
        }
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (args.timeout() != null && !args.timeout().isBlank()) {
            try {
                timeout = Integer.parseInt(args.timeout().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return runPython(args.code(), Math.min(timeout, MAX_TIMEOUT_SECONDS), Boolean.TRUE.equals(args.reset()));
    }

    private ToolResult runPython(String code, int timeoutSeconds, boolean resetRequested) {
        long started = System.nanoTime();
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
                String output = sanitizeOutput(awaitPartialOutput(outputFuture, 2));
                String timeoutMessage = "Code execution timed out after " + timeoutSeconds + " seconds";
                if (!output.isBlank()) {
                    output = output + "\n\n" + timeoutMessage;
                }
                return executionResult("timeout", output.isBlank() ? timeoutMessage : output, -1,
                    timeoutMessage, started, resetRequested);
            }
            String output = awaitCompletedOutput(outputFuture, 5);
            int exitCode = process.exitValue();

            // Hermes parity (code_execution_tool.py:1220-1230): truncate stdout to
            // 50KB head+tail, strip ANSI, and redact secrets before returning.
            output = sanitizeOutput(output);

            if (exitCode != 0) {
                String errorMsg = "Script exited with code " + exitCode;
                return executionResult("error", output, exitCode, errorMsg, started, resetRequested);
            }
            return executionResult("success", output, exitCode, null, started, resetRequested);
        } catch (Exception e) {
            return executionResult("error", "", -1, "Failed to execute code: " + errorMessage(e),
                started, resetRequested);
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

    private ToolResult jsonToolError(String error) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("error", error);
        return new ToolResult(false, response.toString(), error);
    }

    private ToolResult executionResult(String status, String output, int exitCode, String error, long started,
                                       boolean resetRequested) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("status", status);
        response.put("output", output == null ? "" : output);
        response.put("exit_code", exitCode);
        response.put("tool_calls_made", 0);
        response.put("duration_seconds", durationSeconds(started));
        response.put("execution_mode", MODE_LOCAL);
        response.put("kernel_mode", "per_call");
        if (resetRequested) {
            response.put("reset_ignored", true);
            response.put("reset_reason", "java-agent local execution is per-call; no persistent kernel state exists to reset.");
        }
        if (error != null && !error.isBlank()) {
            response.put("error", error);
        }
        String payload = response.toString();
        return "success".equals(status)
            ? ToolResult.ok(payload)
            : new ToolResult(false, payload, error == null || error.isBlank() ? status : error);
    }

    private ToolResult unsupportedExecutionMode(String mode) {
        String error = switch (mode) {
            case MODE_SESSION_KERNEL -> "execute_code mode 'session_kernel' requires Hermes session-persistent kernel runtime. " +
                "java-agent currently supports only local per-call Python execution; use mode='local' here or run this through Hermes.";
            case MODE_REMOTE_RPC -> "execute_code mode 'remote_rpc' requires Hermes terminal-environment file-based RPC runtime. " +
                "java-agent currently supports only local per-call Python execution; use mode='local' here or run this through Hermes.";
            default -> "Unsupported execute_code mode '" + mode + "'. Supported mode in java-agent: local. " +
                "Hermes runtime modes session_kernel and remote_rpc are explicit non-parity gaps here.";
        };
        ObjectNode response = MAPPER.createObjectNode();
        response.put("status", "error");
        response.put("output", "");
        response.put("exit_code", -1);
        response.put("tool_calls_made", 0);
        response.put("duration_seconds", 0);
        response.put("execution_mode", mode);
        response.put("kernel_mode", MODE_SESSION_KERNEL.equals(mode) ? "session" : "unsupported");
        ArrayNode supported = response.putArray("supported_modes");
        supported.add(MODE_LOCAL);
        ArrayNode unsupported = response.putArray("unsupported_hermes_modes");
        unsupported.add(MODE_SESSION_KERNEL);
        unsupported.add(MODE_REMOTE_RPC);
        response.put("error", error);
        return new ToolResult(false, response.toString(), error);
    }

    private String normalizeExecutionMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_LOCAL;
        }
        String normalized = mode.trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        if ("per_call".equals(normalized) || "percall".equals(normalized)) {
            return MODE_LOCAL;
        }
        if ("session".equals(normalized) || "kernel".equals(normalized)) {
            return MODE_SESSION_KERNEL;
        }
        if ("remote".equals(normalized) || "rpc".equals(normalized)) {
            return MODE_REMOTE_RPC;
        }
        return normalized;
    }

    private double durationSeconds(long started) {
        return Math.round(((System.nanoTime() - started) / 1_000_000_000.0) * 100.0) / 100.0;
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName()
            : e.getMessage();
    }

    private JsonNode parseRawArguments(String arguments) {
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String jsonTypeName(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return node.getNodeType().name().toLowerCase();
    }

    private String articleFor(String typeName) {
        return typeName != null && typeName.matches("^[aeiou].*") ? "an" : "a";
    }

    private String awaitCompletedOutput(java.util.concurrent.CompletableFuture<String> outputFuture, int timeoutSeconds)
        throws Exception {
        return outputFuture.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private String awaitPartialOutput(java.util.concurrent.CompletableFuture<String> outputFuture, int timeoutSeconds) {
        try {
            return outputFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            outputFuture.cancel(true);
            return "";
        }
    }

    private String sanitizeOutput(String output) {
        String sanitized = truncateStdout(output);
        sanitized = AnsiStrip.strip(sanitized);
        sanitized = redactor.redact(sanitized);
        return sanitized == null ? "" : sanitized;
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
        scrubChildEnvironment(pb.environment());
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

    void scrubChildEnvironment(Map<String, String> env) {
        Map<String, String> original = new LinkedHashMap<>(env);
        env.clear();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            if (isAllowedChildEnv(entry.getKey())) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
    }

    private boolean isAllowedChildEnv(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        for (String secret : SECRET_ENV_SUBSTRINGS) {
            if (upper.contains(secret)) {
                return false;
            }
        }
        if (HERMES_CHILD_ALLOWED_ENV.contains(upper) || WINDOWS_ESSENTIAL_ENV.contains(upper)) {
            return true;
        }
        for (String prefix : SAFE_ENV_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static class ExecuteCodeArgs {
        @ToolParam(description = "Python code to execute. The hermes_tools module is unavailable; use this for local computation only.")
        private String code;
        @ToolParam(description = "Execution runtime mode. Supported in java-agent: local. Hermes-only modes session_kernel and remote_rpc return explicit unsupported errors.", required = false)
        @JsonAlias({"execution_mode", "executionMode"})
        private String mode;
        @ToolParam(description = "Hermes session-kernel reset flag. In java-agent local per-call mode every execution is already fresh, so the result marks reset_ignored when this is requested.", required = false)
        private Boolean reset;
        @ToolParam(description = "timeout in seconds", required = false)
        private String timeout;

        public String code() { return code; }
        public String mode() { return mode; }
        public Boolean reset() { return reset; }
        public String timeout() { return timeout; }
    }
}
