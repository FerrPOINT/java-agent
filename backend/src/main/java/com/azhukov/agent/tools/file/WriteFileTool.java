package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@AgentTool(
    name = "write_file",
    description = "Write content to a file, completely replacing existing content. Use this instead of echo/cat heredoc in terminal. Creates parent directories automatically. OVERWRITES the entire file — use 'patch' for targeted edits. Refuses invalid JSON/YAML before touching disk. The result's verified:true means the on-disk bytes were confirmed after writing.",
    toolset = "file"
)
@Component
public class WriteFileTool implements ToolHandler {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private static final String READ_DEDUP_STATUS_MESSAGE = "File unchanged since last read. The content from "
        + "the earlier read_file result in this conversation is still current — refer to that instead of re-reading.";

    private final FileSafety fileSafety;
    private final AgentProperties properties;

    public WriteFileTool(AgentProperties properties) {
        this(FileToolSafety.defaultSafety(properties));
    }

    public WriteFileTool(FileSafety fileSafety) {
        this.fileSafety = fileSafety;
        this.properties = null;
    }

    @Autowired
    public WriteFileTool(AgentProperties properties, DefaultFileSafety fileSafety) {
        this.properties = properties;
        this.fileSafety = fileSafety;
    }

    /**
     * Main-config allowed-roots guard (multi-layer with {@link #fileSafety}):
     * allowedPaths configured on THIS tool's properties must also admit the
     * path, even when the shared safety bean was built from another scope.
     */
    private ToolResult ensureWithinPropertiesAllowed(Path path, String rawPath) {
        if (properties == null) {
            return null;
        }
        var allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (String allowedPath : allowed) {
            if (normalized.startsWith(java.nio.file.Paths.get(allowedPath).toAbsolutePath().normalize())) {
                return null;
            }
        }
        return ToolResult.fail("Access denied: path is outside allowed directories or not allowed: " + rawPath);
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        WriteArgs args;
        try {
            args = ToolHandler.parseJson(arguments, WriteArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        if (args.path() == null || args.path().isBlank()) {
            return jsonFail("path is required");
        }
        if (args.content() == null) {
            return jsonFail("content is required");
        }
        if (isInternalFileToolContent(args.content())) {
            return jsonFail(
                "Refusing to write internal read_file display text as file content. "
                    + "Strip read_file line-number prefixes or reconstruct the intended file contents before writing.");
        }

        Path path = FileToolSafety.resolvePath(args.path(), session);
        ToolResult safetyCheck = FileToolSafety.ensureWritable(fileSafety, path, args.path(), args.crossProfile());
        if (safetyCheck != null) {
            return jsonFail(safetyCheck.error());
        }
        ToolResult propertiesCheck = ensureWithinPropertiesAllowed(path, args.path());
        if (propertiesCheck != null) {
            return jsonFail(propertiesCheck.error());
        }
        ToolResult textWriteCheck = FileToolSafety.ensurePlainTextWriteAllowed(path, args.path());
        if (textWriteCheck != null) {
            return jsonFail(textWriteCheck.error());
        }

        try {
            TextFileWriteSupport.WriteOutcome write = TextFileWriteSupport.write(path, args.content());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bytes_written", write.bytesWritten());
            result.put("verified", write.verified());
            result.put("verification", verification(args.content()));
            result.put("resolved_path", write.path().toString());
            result.put("files_modified", List.of(write.path().toString()));
            return jsonOk(result);
        } catch (IOException e) {
            return jsonFail("Failed to write file: " + e.getMessage());
        }
    }

    private static ToolResult jsonFail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        String message = error == null ? "File operation failed" : error;
        result.put("success", false);
        result.put("error", message);
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), message);
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"File operation failed\"}", message);
        }
    }

    private static ToolResult jsonOk(Map<String, Object> result) {
        try {
            return ToolResult.ok(JSON.writeValueAsString(result));
        } catch (IOException e) {
            return ToolResult.ok(String.valueOf(result));
        }
    }

    private static Map<String, Object> verification(String content) {
        String safeContent = content == null ? "" : content;
        List<String> lines = safeContent.isEmpty() ? List.of("") : safeContent.lines().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("first_line", lines.getFirst());
        result.put("last_line", lines.getLast());
        result.put("line_count", safeContent.isEmpty() ? 0 : lines.size());
        return result;
    }

    private static boolean isInternalFileToolContent(String content) {
        return isInternalFileStatusText(content) || looksLikeReadFileLineNumberedContent(content);
    }

    private static boolean isInternalFileStatusText(String content) {
        String stripped = content.strip();
        return stripped.equals(READ_DEDUP_STATUS_MESSAGE)
            || (stripped.contains(READ_DEDUP_STATUS_MESSAGE)
                && stripped.length() <= 2 * READ_DEDUP_STATUS_MESSAGE.length());
    }

    private static boolean looksLikeReadFileLineNumberedContent(String content) {
        List<Integer> numbered = new java.util.ArrayList<>();
        int nonEmptyLines = 0;
        for (String line : content.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            nonEmptyLines++;
            String stripped = line.stripLeading();
            int pipe = stripped.indexOf('|');
            if (pipe <= 0) {
                continue;
            }
            String prefix = stripped.substring(0, pipe);
            if (prefix.chars().allMatch(Character::isDigit)) {
                try {
                    numbered.add(Integer.parseInt(prefix));
                } catch (NumberFormatException ignored) {
                    // Over-large line numbers are not a useful signal here.
                }
            }
        }
        if (nonEmptyLines < 2 || numbered.size() < 2) {
            return false;
        }
        if (((double) numbered.size() / nonEmptyLines) < 0.6) {
            return false;
        }
        int consecutivePairs = 0;
        for (int i = 1; i < numbered.size(); i++) {
            if (numbered.get(i) == numbered.get(i - 1) + 1) {
                consecutivePairs++;
            }
        }
        return consecutivePairs >= numbered.size() - 1;
    }

    public record WriteArgs(
        @ToolParam(description = "file path to write") String path,
        @ToolParam(description = "full file content") String content,
        @ToolParam(description = "Opt out of the cross-profile soft guard. Defaults to false. Set true ONLY after explicit user direction to edit another Hermes profile's skills/plugins/cron/memories — by default these writes are blocked with a warning because they affect a different profile than the one this session is running under.", required = false) @JsonProperty("cross_profile") Boolean crossProfile
    ) {}
}
