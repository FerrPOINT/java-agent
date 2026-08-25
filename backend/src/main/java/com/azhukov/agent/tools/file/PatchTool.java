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
    description = "Targeted find-and-replace edits in files. Use this instead of sed/awk in terminal. Uses fuzzy matching (9 strategies) so minor whitespace/indentation differences won't break it. Returns a unified diff.\n\nREPLACE MODE (mode='replace', default): find a unique string and replace it. REQUIRED PARAMETERS: mode, path, old_string, new_string.\nPATCH MODE (mode='patch'): apply V4A multi-file patches for bulk changes. REQUIRED PARAMETERS: mode, patch.",
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
        String mode = args.mode() == null ? "replace" : args.mode().toLowerCase();
        Path path = hasPath ? Path.of(args.path()).toAbsolutePath().normalize() : null;
        // M13 fix: check the NORMALIZED absolute path — the raw string check
        // was bypassable via /x/../.env and /./.env forms.
        if (path != null && isBlocked(path.toString())) {
            return ToolResult.fail("Patching this path is not allowed: " + args.path());
        }
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

        // p7: Check if new_string is already present in the file content.
        // If so, the patch was likely already applied — return success with info.
        if (!replaceAll && content.contains(newString) && !content.contains(oldString)) {
            return ToolResult.ok("[info: new_string already present, no changes needed]");
        }

        // p11: Multi-match detection — before applying patch, check if old_string
        // appears more than once in the file. If so (and replaceAll is not set
        // and old_string != new_string), return an error instead of silently
        // replacing only the first occurrence.
        // When old_string == new_string, multi-match is a no-op (same string),
        // so we skip the check and let it proceed to the normal patch logic.
        if (!replaceAll && !oldString.equals(newString)) {
            int firstIdx = content.indexOf(oldString);
            if (firstIdx >= 0) {
                int secondIdx = content.indexOf(oldString, firstIdx + 1);
                if (secondIdx >= 0) {
                    // Count total occurrences for the error message
                    int count = 1;
                    int idx = firstIdx;
                    while ((idx = content.indexOf(oldString, idx + 1)) >= 0) {
                        count++;
                    }
                    return ToolResult.fail("[error: old_string matches " + count + " times — must be unique. Use replace_all=true to replace all occurrences]");
                }
            }
        }

        // Strategy 0: exact match — use indexOf to check existence before replacing.
        // This correctly handles the edge case where oldString.equals(newString):
        // replaceFirst/replace would produce identical content, but the string WAS
        // found, so the patch should succeed rather than reporting "not found".
        if (content.indexOf(oldString) >= 0) {
            String updated = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (replace " + (replaceAll ? "all" : "first") + ")");
        }
        // Fuzzy matching strategies
        String[] strategies = {
            "trimmed whitespace", "normalized line endings", "collapsed whitespace",
            "case-insensitive", "trailing whitespace per line", "BOM stripped",
            "tabs to spaces", "empty lines removed"
        };
        // Strategy 1: trim leading/trailing whitespace from old_string
        String trimmed = oldString.strip();
        if (!trimmed.equals(oldString) && content.contains(trimmed)) {
            String updated = replaceAll
                ? content.replace(trimmed, newString)
                : content.replaceFirst(Pattern.quote(trimmed), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (fuzzy: trimmed whitespace)");
        }
        // Strategy 2: normalize line endings (CRLF → LF)
        String normalizedOld = oldString.replace("\r\n", "\n");
        String normalizedContent = content.replace("\r\n", "\n");
        if (!normalizedOld.equals(oldString) && normalizedContent.contains(normalizedOld)) {
            String updated = replaceAll
                ? normalizedContent.replace(normalizedOld, newString)
                : normalizedContent.replaceFirst(Pattern.quote(normalizedOld), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (fuzzy: normalized line endings)");
        }
        // Strategy 3: collapse multiple whitespace runs to single space
        String collapsedOld = oldString.replaceAll("\\s+", " ");
        String collapsedContent = content.replaceAll("\\s+", " ");
        if (!collapsedOld.equals(oldString) && collapsedContent.contains(collapsedOld)) {
            int idx = collapsedContent.indexOf(collapsedOld);
            // Map back to original content — find the match position heuristically
            String updated = replaceAll
                ? content.replaceFirst(Pattern.quote(findOriginalMatch(content, oldString, collapsedOld)), Matcher.quoteReplacement(newString))
                : content.replaceFirst(Pattern.quote(findOriginalMatch(content, oldString, collapsedOld)), Matcher.quoteReplacement(newString));
            if (!updated.equals(content)) {
                Files.writeString(path, updated, StandardCharsets.UTF_8);
                return ToolResult.ok("Patched " + path + " (fuzzy: collapsed whitespace)");
            }
        }
        // Strategy 4: case-insensitive match
        int ciIdx = content.toLowerCase().indexOf(oldString.toLowerCase());
        if (ciIdx >= 0 && !content.contains(oldString)) {
            String actual = content.substring(ciIdx, ciIdx + oldString.length());
            String updated = replaceAll
                ? content.replace(actual, newString)
                : content.replaceFirst(Pattern.quote(actual), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (fuzzy: case-insensitive)");
        }
        // Strategy 5: ignore trailing whitespace per line
        String[] oldLines = oldString.split("\n");
        StringBuilder strippedOld = new StringBuilder();
        for (String line : oldLines) {
            strippedOld.append(line.stripTrailing()).append("\n");
        }
        String strippedOldStr = strippedOld.toString().stripTrailing();
        String[] contentLines = content.split("\n");
        StringBuilder strippedContent = new StringBuilder();
        for (String line : contentLines) {
            strippedContent.append(line.stripTrailing()).append("\n");
        }
        String strippedContentStr = strippedContent.toString();
        if (!strippedOldStr.equals(oldString) && strippedContentStr.contains(strippedOldStr)) {
            // Find the actual match in original content by mapping line positions
            String actualMatch = findOriginalMatchByLines(content, strippedOldStr);
            if (actualMatch != null) {
                String updated = content.replaceFirst(Pattern.quote(actualMatch), Matcher.quoteReplacement(newString));
                if (!updated.equals(content)) {
                    Files.writeString(path, updated, StandardCharsets.UTF_8);
                    return ToolResult.ok("Patched " + path + " (fuzzy: trailing whitespace)");
                }
            }
        }
        // Strategy 6: strip BOM from file content
        String bomStrippedContent = content;
        if (content.startsWith("\uFEFF")) {
            bomStrippedContent = content.substring(1);
        }
        if (!bomStrippedContent.equals(content) && bomStrippedContent.contains(oldString)) {
            String updated = bomStrippedContent.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (fuzzy: BOM stripped)");
        }
        // Strategy 7: normalize tabs to spaces (4 spaces) in old_string
        String tabNormalizedOld = oldString.replace("\t", "    ");
        if (!tabNormalizedOld.equals(oldString) && content.contains(tabNormalizedOld)) {
            String updated = replaceAll
                ? content.replace(tabNormalizedOld, newString)
                : content.replaceFirst(Pattern.quote(tabNormalizedOld), Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Patched " + path + " (fuzzy: tabs to spaces)");
        }
        // Strategy 8: try matching with empty lines removed from old_string
        String noEmptyLinesOld = oldString.lines().filter(l -> !l.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
        if (!noEmptyLinesOld.equals(oldString) && !noEmptyLinesOld.isEmpty() && content.contains(noEmptyLinesOld)) {
            String updated = content.replaceFirst(Pattern.quote(noEmptyLinesOld), Matcher.quoteReplacement(newString));
            if (!updated.equals(content)) {
                Files.writeString(path, updated, StandardCharsets.UTF_8);
                return ToolResult.ok("Patched " + path + " (fuzzy: empty lines removed)");
            }
        }
        return ToolResult.fail("Could not find old_string in file. Tried fuzzy strategies: " + String.join(", ", strategies) + ". Use read_file to verify current content.");
    }

    private String findOriginalMatch(String content, String oldString, String collapsedOld) {
        // Heuristic: find a substring of content that, when collapsed, matches collapsedOld
        int approxLen = collapsedOld.length() + (oldString.length() - collapsedOld.length()) / 2;
        for (int i = 0; i <= content.length() - oldString.length(); i++) {
            String candidate = content.substring(i, Math.min(i + oldString.length() + 20, content.length()));
            if (candidate.replaceAll("\\s+", " ").startsWith(collapsedOld)) {
                return candidate.substring(0, Math.min(collapsedOld.length() + 10, candidate.length()));
            }
        }
        return oldString;
    }

    private String findOriginalMatchByLines(String content, String strippedOld) {
        String[] oldLines = strippedOld.split("\n");
        String[] contentLines = content.split("\n");
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!contentLines[i + j].stripTrailing().equals(oldLines[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return String.join("\n", java.util.Arrays.copyOfRange(contentLines, i, i + oldLines.length));
            }
        }
        return null;
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
        @ToolParam(description = "V4A patch content (patch mode)", required = false) String patch,
        @ToolParam(description = "Opt out of the cross-profile soft guard. Defaults to false. Set true ONLY after explicit user direction to edit another Hermes profile's skills/plugins/cron/memories.", required = false) @JsonProperty("cross_profile") Boolean crossProfile
    ) {}
}
