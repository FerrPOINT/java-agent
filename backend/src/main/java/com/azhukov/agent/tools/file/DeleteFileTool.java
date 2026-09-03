package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AgentTool(
    name = "delete_file",
    description = "Delete a file at the specified path. Paths outside allowed directories are blocked when file safety is enabled.",
    toolset = "file"
)
@Component
public class DeleteFileTool implements ToolHandler {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private static final List<String> BLOCKED_ROOTS = List.of(
        "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/boot", "/dev", "/proc", "/sys"
    );

    private final FileSafety fileSafety;

    public DeleteFileTool(AgentProperties properties) {
        this(FileToolSafety.defaultSafety(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeleteFileTool(FileSafety fileSafety) {
        this.fileSafety = fileSafety;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DeleteArgs args;
        try {
            args = ToolHandler.parseJson(arguments, DeleteArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        if (args.path() == null || args.path().isBlank()) {
            return jsonFail("path is required");
        }

        Path path = FileToolSafety.resolvePath(args.path(), session);
        if (isDeleteBlocked(args.path(), path)) {
            return jsonFail("Deleting this path is not allowed: " + args.path());
        }
        ToolResult safetyCheck = FileToolSafety.ensureWritable(fileSafety, path, args.path(), false);
        if (safetyCheck != null) {
            return jsonFail(safetyCheck.error());
        }
        if (!Files.exists(path)) {
            return jsonFail("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            return jsonFail("Refusing to delete directory: " + path);
        }

        try {
            Files.delete(path);
            return ToolResult.ok("Deleted file: " + path);
        } catch (IOException e) {
            return jsonFail("Failed to delete file: " + e.getMessage());
        }
    }

    private static ToolResult jsonFail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        String message = error == null ? "Delete failed" : error;
        result.put("success", false);
        result.put("error", message);
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), message);
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Delete failed\"}", message);
        }
    }

    private boolean isDeleteBlocked(String rawPath, Path path) {
        String raw = rawPath.replace('\\', '/');
        String normalized = path.toString().replace('\\', '/');
        for (String root : BLOCKED_ROOTS) {
            if (matchesBlockedRoot(raw, root) || matchesBlockedRoot(normalized, root)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBlockedRoot(String path, String root) {
        return path.equals(root)
            || path.startsWith(root + "/")
            || path.endsWith(root)
            || path.contains(":/" + root.substring(1) + "/");
    }

    private record DeleteArgs(String path) {}
}
