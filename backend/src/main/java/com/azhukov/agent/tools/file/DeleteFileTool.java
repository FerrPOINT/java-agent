package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@AgentTool(
    name = "delete_file",
    description = "Delete a file at the specified path. Paths outside allowed directories are blocked when file safety is enabled.",
    toolset = "file"
)
@Component
public class DeleteFileTool implements ToolHandler {

    private static final List<String> BLOCKED_PATHS = List.of(
        "/.env", "/etc/shadow", "/etc/passwd", "/root/.ssh",
        "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/boot", "/dev", "/proc", "/sys"
    );

    private final AgentProperties properties;

    public DeleteFileTool(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DeleteArgs args = ToolHandler.parseJson(arguments, DeleteArgs.class);
        if (args.path() == null || args.path().isBlank()) {
            return ToolResult.fail("path is required");
        }

        Path path = Path.of(args.path()).toAbsolutePath().normalize();
        if (isBlocked(path)) {
            return ToolResult.fail("Deleting this path is not allowed: " + args.path());
        }
        if (!isPathAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories: " + args.path());
        }
        if (!Files.exists(path)) {
            return ToolResult.fail("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.fail("Refusing to delete directory: " + path);
        }

        try {
            Files.delete(path);
            return ToolResult.ok("Deleted file: " + path);
        } catch (IOException e) {
            return ToolResult.fail("Failed to delete file: " + e.getMessage());
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
        String s = path.toString();
        for (String allowedPath : allowed) {
            String ap = Path.of(allowedPath).toAbsolutePath().normalize().toString();
            if (s.startsWith(ap)) {
                return true;
            }
        }
        return false;
    }

    private record DeleteArgs(String path) {}
}