package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    description = "Write content to a file, completely replacing existing content. Use this instead of echo/cat heredoc in terminal. Creates parent directories automatically. OVERWRITES the entire file — use 'patch' for targeted edits. The result includes a verification echo with the first and last line of the written content.",
    toolset = "file"
)
@Component
public class WriteFileTool implements ToolHandler {

    private final AgentProperties properties;
    private final DefaultFileSafety fileSafety;

    public WriteFileTool(AgentProperties properties, DefaultFileSafety fileSafety) {
        this.properties = properties;
        this.fileSafety = fileSafety;
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
        // Hermes parity (agent/file_safety.py build_write_denied_paths):
        // the full sensitive-path denylist (~20 exact paths + 10 directory
        // prefixes) lives in DefaultFileSafety — the tool-local 4-path mini
        // list let writes to ~/.pgpass, /etc/sudoers, ~/.aws/* through.
        if (!fileSafety.isWriteAllowed(path)) {
            return ToolResult.fail("Write denied: '" + args.path()
                + "' is a protected system/credential file.");
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

            // p9: Verification echo — include first and last line of the written content.
            String verification = buildVerificationEcho(args.content());
            return ToolResult.ok("Wrote " + args.content().length() + " characters to " + path + "\n" + verification);
        } catch (IOException e) {
            return ToolResult.fail("Failed to write file: " + e.getMessage());
        }
    }

    /**
     * p9: Build a verification echo string containing the first and last line
     * of the written content. Handles single-line and empty content.
     */
    private static String buildVerificationEcho(String content) {
        if (content == null || content.isEmpty()) {
            return "[verified: first line: \"\", last line: \"\"]";
        }
        String[] lines = content.split("\n", -1);
        String firstLine = lines[0];
        String lastLine = lines[lines.length - 1];
        return "[verified: first line: \"" + firstLine + "\", last line: \"" + lastLine + "\"]";
    }

    private boolean isPathAllowed(Path path) {
        // Denylists are invariant safety boundaries and must apply even when
        // configurable root restrictions are disabled.
        if (!fileSafety.isWriteAllowed(path)) {
            return false;
        }
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
        @ToolParam(description = "full file content") String content,
        @ToolParam(description = "Opt out of the cross-profile soft guard. Defaults to false. Set true ONLY after explicit user direction to edit another Hermes profile's skills/plugins/cron/memories — by default these writes are blocked with a warning because they affect a different profile than the one this session is running under.", required = false) @JsonProperty("cross_profile") Boolean crossProfile
    ) {}
}
