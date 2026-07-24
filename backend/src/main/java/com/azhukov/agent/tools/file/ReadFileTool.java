package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@AgentTool(
    name = "read_file",
    description = "Read a text file with optional offset and limit. Returns content with line numbers. Paths outside allowed directories are blocked when file safety is enabled.",
    toolset = "file"
)
public class ReadFileTool implements ToolHandler {

    private final AgentProperties properties;

    public ReadFileTool(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ReadFileArgs args = ToolHandler.parseJson(arguments, ReadFileArgs.class);
        Path path = Path.of(args.path()).toAbsolutePath().normalize();

        if (!isPathAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories: " + args.path());
        }

        if (!Files.exists(path)) {
            return ToolResult.fail("File not found: " + args.path());
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("Not a file: " + args.path());
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = Math.max(1, args.offset());
            int start = offset - 1;
            int limit = args.limit() > 0 ? args.limit() : Integer.MAX_VALUE;
            int end = Math.min(lines.size(), start + limit);

            if (start >= lines.size()) {
                return ToolResult.ok("");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.fail("Failed to read file: " + e.getMessage());
        }
    }

    private boolean isPathAllowed(Path path) {
        if (!properties.getSecurity().isFileSafetyEnabled()) {
            return true;
        }
        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        for (String base : allowed) {
            Path allowedPath = Path.of(base).toAbsolutePath().normalize();
            if (path.startsWith(allowedPath)) {
                return true;
            }
        }
        return false;
    }

    public record ReadFileArgs(
        @ToolParam(description = "absolute or relative path to the file") String path,
        @ToolParam(description = "starting line number (1-based)") int offset,
        @ToolParam(description = "maximum number of lines to read") int limit
    ) {}
}
