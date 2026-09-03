package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.FileSafety;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private final FileSafety fileSafety;

    public PatchTool() {
        this(new AgentProperties());
    }

    public PatchTool(AgentProperties properties) {
        this(FileToolSafety.defaultSafety(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PatchTool(FileSafety fileSafety) {
        this.fileSafety = fileSafety;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        PatchArgs args;
        try {
            args = ToolHandler.parseJson(arguments, PatchArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        boolean hasPath = args.path() != null && !args.path().isBlank();
        if (!hasPath && !"patch".equals(args.mode())) {
            return jsonFail("path is required");
        }
        String mode = args.mode() == null ? "replace" : args.mode().toLowerCase();
        Path path = hasPath ? FileToolSafety.resolvePath(args.path(), session) : null;
        if (path != null) {
            ToolResult safetyCheck = FileToolSafety.ensureWritable(fileSafety, path, args.path(), args.crossProfile());
            if (safetyCheck == null) {
                safetyCheck = FileToolSafety.ensureNotProtectedInstruction(path, args.path(), args.crossProfile());
            }
            if (safetyCheck != null) {
                return jsonFail(safetyCheck.error());
            }
        }
        try {
            if ("replace".equals(mode)) {
                if (args.oldString() == null || args.newString() == null) {
                    return jsonFail("old_string and new_string are required for replace mode");
                }
                if (path == null || !Files.exists(path)) {
                    return jsonFail("File not found: " + args.path());
                }
                ToolResult textWriteCheck = FileToolSafety.ensurePlainTextWriteAllowed(path, args.path());
                if (textWriteCheck != null) {
                    return jsonFail(textWriteCheck.error());
                }
                return replace(path, args.oldString(), args.newString(), args.replaceAll());
            }
            if ("patch".equals(mode)) {
                if (args.patch() == null || args.patch().isBlank()) {
                    return jsonFail("patch content is required for patch mode");
                }
                return applyV4a(args.patch(), args.crossProfile(), session);
            }
            return jsonFail("Unknown mode: " + args.mode());
        } catch (IOException e) {
            return jsonFail("Patch failed: " + e.getMessage());
        }
    }

    private ToolResult replace(Path path, String oldString, String newString, boolean replaceAll) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);

        // p7: Check if new_string is already present in the file content.
        // If so, the patch was likely already applied — return success with info.
        if (!replaceAll && content.contains(newString) && !content.contains(oldString)) {
            Map<String, Object> result = patchResult(true);
            result.put("no_change", true);
            result.put("note", "new_string already present, no changes needed");
            return jsonOk(result);
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
                    return jsonFail("[error: old_string matches " + count + " times — must be unique. Use replace_all=true to replace all occurrences]");
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
            return writePatched(path, updated, "replace " + (replaceAll ? "all" : "first"));
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
            return writePatched(path, updated, "fuzzy: trimmed whitespace");
        }
        // Strategy 2: normalize line endings (CRLF → LF)
        String normalizedOld = oldString.replace("\r\n", "\n");
        String normalizedContent = content.replace("\r\n", "\n");
        if (!normalizedOld.equals(oldString) && normalizedContent.contains(normalizedOld)) {
            String updated = replaceAll
                ? normalizedContent.replace(normalizedOld, newString)
                : normalizedContent.replaceFirst(Pattern.quote(normalizedOld), Matcher.quoteReplacement(newString));
            return writePatched(path, updated, "fuzzy: normalized line endings");
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
                return writePatched(path, updated, "fuzzy: collapsed whitespace");
            }
        }
        // Strategy 4: case-insensitive match
        int ciIdx = content.toLowerCase().indexOf(oldString.toLowerCase());
        if (ciIdx >= 0 && !content.contains(oldString)) {
            String actual = content.substring(ciIdx, ciIdx + oldString.length());
            String updated = replaceAll
                ? content.replace(actual, newString)
                : content.replaceFirst(Pattern.quote(actual), Matcher.quoteReplacement(newString));
            return writePatched(path, updated, "fuzzy: case-insensitive");
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
                    return writePatched(path, updated, "fuzzy: trailing whitespace");
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
            return writePatched(path, updated, "fuzzy: BOM stripped");
        }
        // Strategy 7: normalize tabs to spaces (4 spaces) in old_string
        String tabNormalizedOld = oldString.replace("\t", "    ");
        if (!tabNormalizedOld.equals(oldString) && content.contains(tabNormalizedOld)) {
            String updated = replaceAll
                ? content.replace(tabNormalizedOld, newString)
                : content.replaceFirst(Pattern.quote(tabNormalizedOld), Matcher.quoteReplacement(newString));
            return writePatched(path, updated, "fuzzy: tabs to spaces");
        }
        // Strategy 8: try matching with empty lines removed from old_string
        String noEmptyLinesOld = oldString.lines().filter(l -> !l.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
        if (!noEmptyLinesOld.equals(oldString) && !noEmptyLinesOld.isEmpty() && content.contains(noEmptyLinesOld)) {
            String updated = content.replaceFirst(Pattern.quote(noEmptyLinesOld), Matcher.quoteReplacement(newString));
            if (!updated.equals(content)) {
                return writePatched(path, updated, "fuzzy: empty lines removed");
            }
        }
        return jsonFail("Could not find old_string in file. Tried fuzzy strategies: " + String.join(", ", strategies) + ". Use read_file to verify current content.");
    }

    private ToolResult writePatched(Path path, String updated, String label) throws IOException {
        String before = Files.readString(path, StandardCharsets.UTF_8);
        TextFileWriteSupport.WriteOutcome write = TextFileWriteSupport.write(path, updated);
        Map<String, Object> result = patchResult(true);
        result.put("diff", compactDiff(write.path(), before, write.content()));
        result.put("files_modified", List.of(write.path().toString()));
        result.put("resolved_path", write.path().toString());
        result.put("note", label);
        return jsonOk(result);
    }

    private String findOriginalMatch(String content, String oldString, String collapsedOld) {
        // Heuristic: find a substring of content that, when collapsed, matches collapsedOld
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

    private ToolResult applyV4a(String patchText, Boolean crossProfile, Session session) throws IOException {
        List<String> errors = new ArrayList<>();
        List<V4aOperation> operations = parseV4aOperations(patchText, errors, session);
        if (operations.isEmpty() && errors.isEmpty()) {
            return jsonFail("No V4A file operations found");
        }
        errors.addAll(validateV4aOperations(patchText, operations, crossProfile));
        if (!errors.isEmpty()) {
            return jsonFail(String.join("\n", errors));
        }

        List<String> filesModified = new ArrayList<>();
        List<String> filesCreated = new ArrayList<>();
        List<String> filesDeleted = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (V4aOperation operation : operations) {
            String op = operation.op();
            String pathStr = operation.rawPath();
            Path path = operation.path();
            if ("Add".equals(op)) {
                String content = extractContent(patchText, operation.contentStart());
                TextFileWriteSupport.write(path, content);
                filesCreated.add(pathStr);
            } else if ("Delete".equals(op)) {
                Files.delete(path);
                filesDeleted.add(pathStr);
            } else if ("Update".equals(op)) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String updated = applyHunks(content, parseHunks(extractDiff(patchText, operation.contentStart())));
                TextFileWriteSupport.WriteOutcome write = TextFileWriteSupport.write(path, updated);
                appendDiff(diff, compactDiff(write.path(), content, write.content()));
                filesModified.add(pathStr);
            } else if ("Move".equals(op)) {
                moveFile(path, operation.destination());
                filesModified.add(pathStr + " -> " + operation.rawDestination());
            }
        }
        Map<String, Object> result = patchResult(true);
        if (!diff.isEmpty()) {
            result.put("diff", diff.toString());
        }
        if (!filesModified.isEmpty()) {
            result.put("files_modified", filesModified);
        }
        if (!filesCreated.isEmpty()) {
            result.put("files_created", filesCreated);
        }
        if (!filesDeleted.isEmpty()) {
            result.put("files_deleted", filesDeleted);
        }
        return jsonOk(result);
    }

    private static Map<String, Object> patchResult(boolean success) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        return result;
    }

    private static ToolResult jsonOk(Map<String, Object> result) {
        try {
            return ToolResult.ok(JSON.writeValueAsString(result));
        } catch (IOException e) {
            return ToolResult.ok(String.valueOf(result));
        }
    }

    private static ToolResult jsonFail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        String message = error == null ? "Patch failed" : error;
        result.put("success", false);
        result.put("error", message);
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), message);
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Patch failed\"}", message);
        }
    }

    private static void appendDiff(StringBuilder target, String diff) {
        if (diff == null || diff.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append("\n");
        }
        target.append(diff);
    }

    private static String compactDiff(Path path, String before, String after) {
        if (before.equals(after)) {
            return "";
        }
        String[] beforeLines = before.split("\\R", -1);
        String[] afterLines = after.split("\\R", -1);
        int first = 0;
        while (first < beforeLines.length && first < afterLines.length
            && beforeLines[first].equals(afterLines[first])) {
            first++;
        }

        int beforeLast = beforeLines.length - 1;
        int afterLast = afterLines.length - 1;
        while (beforeLast >= first && afterLast >= first
            && beforeLines[beforeLast].equals(afterLines[afterLast])) {
            beforeLast--;
            afterLast--;
        }

        int context = 3;
        int contextStart = Math.max(0, first - context);
        int beforeEnd = Math.min(beforeLines.length, beforeLast + context + 2);
        int afterEnd = Math.min(afterLines.length, afterLast + context + 2);

        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(path).append("\n");
        diff.append("+++ ").append(path).append("\n");
        diff.append("@@ -").append(contextStart + 1).append(" +").append(contextStart + 1).append(" @@\n");
        for (int i = contextStart; i < first; i++) {
            diff.append(" ").append(beforeLines[i]).append("\n");
        }
        for (int i = first; i < beforeEnd; i++) {
            diff.append("-").append(beforeLines[i]).append("\n");
        }
        for (int i = first; i < afterEnd; i++) {
            diff.append("+").append(afterLines[i]).append("\n");
        }
        return diff.toString().stripTrailing();
    }

    private List<V4aOperation> parseV4aOperations(String patchText, List<String> errors, Session session) {
        Matcher m = V4A_HEADER.matcher(patchText);
        List<V4aOperation> operations = new ArrayList<>();
        while (m.find()) {
            String op = m.group(1);
            String pathSpec = m.group(2).trim();
            String rawPath = pathSpec;
            String rawDestination = null;
            if ("Move".equals(op)) {
                String[] parts = pathSpec.split("\\s+->\\s+", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    errors.add("Invalid move header: " + pathSpec);
                    continue;
                }
                rawPath = parts[0].trim();
                rawDestination = parts[1].trim();
            }
            String traversalError = v4aTraversalError(rawPath);
            if (traversalError != null) {
                errors.add(traversalError);
                continue;
            }
            if (rawDestination != null) {
                traversalError = v4aTraversalError(rawDestination);
                if (traversalError != null) {
                    errors.add(traversalError);
                    continue;
                }
            }

            try {
                Path path = toPatchPath(rawPath, session);
                Path destination = rawDestination == null ? null : toPatchPath(rawDestination, session);
                operations.add(new V4aOperation(op, rawPath, path, rawDestination, destination, m.end()));
            } catch (IllegalArgumentException e) {
                errors.add("Invalid path in " + op.toLowerCase() + " operation '" + pathSpec + "': " + e.getMessage());
            }
        }
        return operations;
    }

    private String v4aTraversalError(String rawPath) {
        if (!hasTraversalComponent(rawPath)) {
            return null;
        }
        return "V4A patch header contains '..' traversal: " + rawPath
            + ". Use the agent cwd-relative path without '..' or an absolute path in V4A file headers.";
    }

    private boolean hasTraversalComponent(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String normalized = rawPath.replace('\\', '/');
        for (String part : normalized.split("/+")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    private Path toPatchPath(String path, Session session) {
        return FileToolSafety.resolvePath(path, session);
    }

    private List<String> validateV4aOperations(String patchText, List<V4aOperation> operations, Boolean crossProfile) {
        List<String> errors = new ArrayList<>();
        for (V4aOperation operation : operations) {
            errors.addAll(validateWritableTarget(operation.path(), operation.rawPath(), crossProfile));
            if ("Move".equals(operation.op()) && operation.destination() != null) {
                errors.addAll(validateWritableTarget(operation.destination(), operation.rawDestination(), crossProfile));
            }
            if ("Add".equals(operation.op()) || "Update".equals(operation.op())) {
                ToolResult textWriteCheck = FileToolSafety.ensurePlainTextWriteAllowed(operation.path(), operation.rawPath());
                if (textWriteCheck != null) {
                    errors.add(textWriteCheck.error());
                }
            }

            if ("Add".equals(operation.op())) {
                if (Files.exists(operation.path())) {
                    errors.add("File already exists for add: " + operation.rawPath());
                } else {
                    validateAddOperation(patchText, operation, errors);
                }
            } else if ("Delete".equals(operation.op())) {
                if (!Files.exists(operation.path())) {
                    errors.add("File not found for delete: " + operation.rawPath());
                }
            } else if ("Update".equals(operation.op())) {
                validateUpdateOperation(patchText, operation, errors);
            } else if ("Move".equals(operation.op())) {
                validateMoveOperation(operation, errors);
            }
        }
        return errors;
    }

    private List<String> validateWritableTarget(Path path, String rawPath, Boolean crossProfile) {
        ToolResult safetyCheck = FileToolSafety.ensureWritable(fileSafety, path, rawPath, crossProfile);
        if (safetyCheck == null) {
            safetyCheck = FileToolSafety.ensureNotProtectedInstruction(path, rawPath, crossProfile);
        }
        if (safetyCheck != null) {
            return List.of(safetyCheck.error());
        }
        return List.of();
    }

    private void validateAddOperation(String patchText, V4aOperation operation, List<String> errors) {
        try {
            TextFileWriteSupport.validateCandidate(
                operation.path(),
                extractContent(patchText, operation.contentStart()));
        } catch (IOException e) {
            errors.add("Failed to add " + operation.rawPath() + ": " + e.getMessage());
        }
    }

    private void validateUpdateOperation(String patchText, V4aOperation operation, List<String> errors) {
        if (!Files.exists(operation.path())) {
            errors.add("File not found for update: " + operation.rawPath());
            return;
        }
        try {
            String content = Files.readString(operation.path(), StandardCharsets.UTF_8);
            String updated = applyHunks(content, parseHunks(extractDiff(patchText, operation.contentStart())));
            TextFileWriteSupport.validateCandidate(operation.path(), updated);
        } catch (Exception e) {
            errors.add("Failed to update " + operation.rawPath() + ": " + e.getMessage());
        }
    }

    private void validateMoveOperation(V4aOperation operation, List<String> errors) {
        if (operation.destination() == null) {
            errors.add("Missing destination for move: " + operation.rawPath());
            return;
        }
        if (!Files.exists(operation.path())) {
            errors.add("File not found for move: " + operation.rawPath());
        }
        if (Files.exists(operation.destination())) {
            errors.add("Destination already exists for move: " + operation.rawDestination());
        }
    }

    private void moveFile(Path source, Path destination) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
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

    private List<String[]> parseHunks(String section) {
        List<String[]> hunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : section.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("***")) {
                continue;
            }
            if (line.startsWith("@@")) {
                addParsedHunk(hunks, current);
                continue;
            }
            current.append(line).append("\n");
        }
        addParsedHunk(hunks, current);
        return hunks;
    }

    private void addParsedHunk(List<String[]> hunks, StringBuilder current) {
        if (current.isEmpty()) {
            return;
        }
        hunks.add(parseDiff(current.toString()));
        current.setLength(0);
    }

    private String applyHunks(String content, List<String[]> hunks) {
        if (hunks.isEmpty()) {
            throw new IllegalArgumentException("No update hunks found");
        }
        String updated = content;
        for (String[] hunk : hunks) {
            updated = applyDiff(updated, hunk[0], hunk[1]);
        }
        return updated;
    }

    private String applyDiff(String content, String oldSection, String newSection) {
        if (content.contains(oldSection)) {
            return content.replaceFirst(Pattern.quote(oldSection), Matcher.quoteReplacement(newSection));
        }
        throw new IllegalArgumentException("Could not match old section for update");
    }

    private record V4aOperation(
        String op,
        String rawPath,
        Path path,
        String rawDestination,
        Path destination,
        int contentStart
    ) {}

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
