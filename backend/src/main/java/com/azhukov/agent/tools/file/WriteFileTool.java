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
import java.nio.file.StandardOpenOption;
import java.util.List;

@AgentTool(
    name = "write_file",
    description = "Write content to a file, completely replacing existing content. Use this instead of echo/cat heredoc in terminal. Creates parent directories automatically. Paths outside allowed directories are blocked when file safety is enabled.",
    toolset = "file"
)
@Component
public class WriteFileTool implements ToolHandler {

    private static final List<String> BLOCKED_PATHS = List.of("/.env", "/etc/shadow", "/etc/passwd", "/root/.ssh");

    private final AgentProperties properties;

    public WriteFileTool(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        WriteArgs args = ToolHandler.parseJson(arguments, WriteArgs.class);
        if (args.path() == null || args.path().isBlank()) {
            return ToolResult.fail("path is required");
        }
        if (args.content() == null) {
            return ToolResult.fail("content is required");
        }

        Path path = Path.of(args.path()).toAbsolutePath().normalize();
        if (isBlocked(path)) {
            return ToolResult.fail("Writing to this path is not allowed: " + args.path());
        }
        if (!isPathAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories: " + args.path());
        }

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, args.content(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.ok("Wrote " + args.content().length() + " characters to " + path);
        } catch (IOException e) {
            return ToolResult.fail("Failed to write file: " + e.getMessage());
        }
    }

    private boolean isBlocked(Path path) {
        String s = path.toString();
        return BLOCKED_PATHS.stream().anyMatch(s::startsWith);
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

    public record WriteArgs(
        @ToolParam(description = "file path to write") String path,
        @ToolParam(description = "full file content") String content
    ) {}
}
