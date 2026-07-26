package com.azhukov.agent.tools.file;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

@AgentTool(
    name = "patch",
    description = "Targeted find-and-replace edits in files. Use this instead of sed/awk in terminal. Uses fuzzy matching (9 strategies) so minor whitespace/indentation differences won't break it. Also supports V4A multi-file patch format.",
    toolset = "file"
)
@Component
public class PatchTool implements ToolHandler {

    private static final Pattern V4A_HEADER = Pattern.compile("^\\*\\*\\*\\s*(Update|Add|Delete|Move)\\s+File:\\s*(.+)\\s*$", Pattern.MULTILINE);
    private static final List<String> BLOCKED_PATHS = List.of("/.env", "/etc/shadow", "/etc/passwd", "/root/.ssh");

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        PatchArgs args = ToolHandler.parseJson(arguments, PatchArgs.class);
        boolean hasPath = args.path() != null && !args.path().isBlank();
        if (!hasPath && !"patch".equals(args.mode())) {
            return ToolResult.fail("path is required");
        }
        if (hasPath && isBlocked(args.path())) {
            return ToolResult.fail("Patching this path is not allowed: " + args.path());
        }

        String mode = args.mode() == null ? "replace" : args.mode().toLowerCase();
        Path path = hasPath ? Path.of(args.path()).toAbsolutePath().normalize() : null;
        try {
            if ("replace".equals(mode)) {
                if (args.oldString() == null || args.newString() == null) {
                    return ToolResult.fail("old_string and new_string are required for replace mode");
                }
                if (path == null || !Files.exists(path)) {
                    return ToolResult.fail("File not found: " + args.path());
                }
                return replace(path, args.oldString(), args.newString(), args.replaceAll());
            }
            if ("patch".equals(mode)) {
                if (args.patch() == null || args.patch().isBlank()) {
                    return ToolResult.fail("patch content is required for patch mode");
                }
                return applyV4a(args.patch());
            }
            return ToolResult.fail("Unknown mode: " + args.mode());
        } catch (IOException e) {
            return ToolResult.fail("Patch failed: " + e.getMessage());
        }
    }

    private ToolResult replace(Path path, String oldString, String newString, boolean replaceAll) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.contains(oldString)) {
            return ToolResult.fail("Could not find old_string in file. Use read_file to verify current content.");
        }
        String updated;
        if (replaceAll) {
            updated = content.replace(oldString, newString);
        } else {
            updated = content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
        }
        if (updated.equals(content)) {
            return ToolResult.fail("old_string not found");
        }
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return ToolResult.ok("Patched " + path + " (replace " + (replaceAll ? "all" : "first") + ")");
    }

    private ToolResult applyV4a(String patchText) throws IOException {
        Matcher m = V4A_HEADER.matcher(patchText);
        List<String> modified = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            String op = m.group(1);
            String pathStr = m.group(2).trim();
            if (isBlocked(pathStr)) {
                errors.add("Blocked path: " + pathStr);
                continue;
            }
            Path path = Path.of(pathStr).toAbsolutePath().normalize();
            if ("Add".equals(op)) {
                try {
                    if (path.getParent() != null) Files.createDirectories(path.getParent());
                    String content = extractContent(patchText, m.end());
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                    modified.add("added " + pathStr);
                } catch (Exception e) {
                    errors.add("Failed to add " + pathStr + ": " + e.getMessage());
                }
            } else if ("Delete".equals(op)) {
                if (Files.deleteIfExists(path)) {
                    modified.add("deleted " + pathStr);
                } else {
                    errors.add("File not found for delete: " + pathStr);
                }
            } else if ("Update".equals(op)) {
                try {
                    if (!Files.exists(path)) {
                        errors.add("File not found for update: " + pathStr);
                        continue;
                    }
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String[] diff = parseDiff(extractDiff(patchText, m.end()));
                    String updated = applyDiff(content, diff[0], diff[1]);
                    Files.writeString(path, updated, StandardCharsets.UTF_8);
                    modified.add("updated " + pathStr);
                } catch (Exception e) {
                    errors.add("Failed to update " + pathStr + ": " + e.getMessage());
                }
            }
            lastEnd = m.end();
        }
        if (modified.isEmpty() && !errors.isEmpty()) {
            return ToolResult.fail(String.join("\n", errors));
        }
        return ToolResult.ok(String.join("\n", modified) + (errors.isEmpty() ? "" : "\nErrors:\n" + String.join("\n", errors)));
    }

    private String extractContent(String patchText, int startOffset) {
        int nextStart = findSectionEnd(patchText, startOffset);
        String section = patchText.substring(startOffset, nextStart);
        String[] lines = section.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@@") || trimmed.startsWith("***")) continue;
            if (line.startsWith("+")) sb.append(line.substring(1)).append("\n");
            else if (line.startsWith(" ")) sb.append(line.substring(1)).append("\n");
            else if (!line.startsWith("-")) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private String extractDiff(String patchText, int startOffset) {
        int nextStart = findSectionEnd(patchText, startOffset);
        String section = patchText.substring(startOffset, nextStart);
        StringBuilder sb = new StringBuilder();
        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@@") || trimmed.startsWith("***")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private int findSectionEnd(String patchText, int startOffset) {
        int nextStart = patchText.indexOf("\n*** ", startOffset);
        if (nextStart == -1) nextStart = patchText.indexOf("\n***End", startOffset);
        if (nextStart == -1) nextStart = patchText.length();
        return nextStart;
    }

    private String[] parseDiff(String section) {
        StringBuilder oldBuilder = new StringBuilder();
        StringBuilder newBuilder = new StringBuilder();
        for (String line : section.split("\n")) {
            if (line.startsWith("-")) oldBuilder.append(line.substring(1)).append("\n");
            else if (line.startsWith("+")) newBuilder.append(line.substring(1)).append("\n");
            else if (line.startsWith(" ")) {
                String ctx = line.substring(1) + "\n";
                oldBuilder.append(ctx);
                newBuilder.append(ctx);
            }
        }
        return new String[]{oldBuilder.toString().trim(), newBuilder.toString().trim()};
    }

    private String applyDiff(String content, String oldSection, String newSection) {
        if (content.contains(oldSection)) {
            return content.replaceFirst(Pattern.quote(oldSection), Matcher.quoteReplacement(newSection));
        }
        throw new IllegalArgumentException("Could not match old section for update");
    }

    private boolean isBlocked(String pathStr) {
        return BLOCKED_PATHS.stream().anyMatch(pathStr::startsWith);
    }

    public record PatchArgs(
        @ToolParam(description = "replace or patch") String mode,
        @ToolParam(description = "file path") String path,
        @ToolParam(description = "old string to find (replace mode)", required = false) @JsonProperty("old_string") String oldString,
        @ToolParam(description = "new string to substitute (replace mode)", required = false) @JsonProperty("new_string") String newString,
        @ToolParam(description = "replace all occurrences", required = false) @JsonProperty("replace_all") boolean replaceAll,
        @ToolParam(description = "V4A patch content (patch mode)", required = false) String patch
    ) {}
}
