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
    description = "Run a Python script that calls Hermes tools programmatically. Use when you need 3+ tool calls with logic between them: filtering/reducing large outputs before they enter context, conditional branching, or loops (N pages/files, retry on failure). Use normal tool calls for single calls, results you must reason over in full, or anything needing user interaction.\n\nAvailable via `from hermes_tools import ...`:\n\n  web_search(query: str, limit: int = 5) -> dict\n    Returns {\"data\": {\"web\": [{\"url\", \"title\", \"description\"}, ...]}}\n  web_extract(urls: list[str], char_limit: int = None) -> dict\n    Returns {\"results\": [{\"url\", \"title\", \"content\", \"error\"}, ...]} where content is markdown.\n    No LLM summarization. Pages over char_limit (default 15000) are head+tail truncated; full text stored on disk (path in the content footer).\n  read_file(path: str, offset: int = 1, limit: int = 2000) -> dict\n    Lines are 1-indexed. Returns {\"content\": \"...\", \"total_lines\": N}\n  write_file(path: str, content: str) -> dict\n    Always overwrites the entire file.\n  search_files(pattern: str, target=\"content\", path=\".\", file_glob=None, limit=50) -> dict\n    target: \"content\" (search inside files) or \"files\" (find files by name). Returns {\"matches\": [...]}\n  patch(path: str, old_string: str, new_string: str, replace_all: bool = False) -> dict\n    Replaces old_string with new_string in the file.\n  terminal(command: str, timeout=None, workdir=None) -> dict\n    Foreground only (no background/pty). Returns {\"output\": \"...\", \"exit_code\": N}\n\nLimits: 5-minute timeout, 50KB stdout cap, max 50 tool calls per script. terminal() is foreground-only (no background or pty).\n\nScripts run in the session's working directory with the active venv's python, so project deps (pandas, etc.) and relative paths work like in terminal().\n\nPrint your final result to stdout; stdlib (json, re, csv, datetime, ...) is available for processing.\n\nBuilt-in helpers (no import): json_parse(text) — tolerant json.loads for terminal() output; shell_quote(s) — shlex.quote for dynamic shell args; retry(fn, max_attempts=3, delay=2) — exponential backoff for transient failures.",
    toolset = "coding"
)
@Component
public class ExecuteCodeTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExecuteCodeArgs args = ToolHandler.parseJson(arguments, ExecuteCodeArgs.class);
        if (args.code() == null || args.code().isBlank()) {
            return ToolResult.fail(
                "execute_code requires Python source in 'code'. " +
                "Use terminal(command=...) for shell commands; " +
                "for Python, retry as execute_code(code=...).");
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
