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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@AgentTool(
    name = "search_files",
    description = "Search file contents or find files by name. Use this instead of grep/rg/find/ls in terminal. Ripgrep-backed, faster than shell equivalents.\n\nContent search (target='content'): Regex search inside files. Output modes: full matches with line numbers, file paths only, or match counts.\n\nFile search (target='files'): Find files by glob pattern (e.g., '*.py', '*config*'). Also use this instead of ls — results sorted by modification time.",
    toolset = "file"
)
@Component
public class SearchFilesTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
        String target = args.target() == null ? "content" : args.target().toLowerCase();
        Path base = Path.of(args.path() == null ? "." : args.path()).toAbsolutePath().normalize();

        if (!Files.exists(base)) {
            return ToolResult.fail("Path not found: " + args.path());
        }

        try {
            if ("files".equals(target)) {
                return findFiles(base, args.pattern(), args.limit(), args.offset());
            }
            String outputMode = args.outputMode() == null ? "content" : args.outputMode().toLowerCase();
            if ("count".equals(outputMode)) {
                return countMatches(base, args.pattern(), args.fileGlob());
            }
            return searchContent(base, args.pattern(), args.fileGlob(), args.limit(), args.offset(), args.context());
        } catch (PatternSyntaxException e) {
            return ToolResult.fail("Invalid regex pattern: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.fail("Search failed: " + e.getMessage());
        }
    }

    private ToolResult findFiles(Path base, String pattern, int limit, int offset) throws IOException {
        String glob = pattern == null || pattern.isBlank() ? "*" : pattern;
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.find(base, Integer.MAX_VALUE,
                (path, attrs) -> path.getFileName().toString().matches(globToRegex(glob)))) {
            stream.sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                .skip(offset)
                .limit(limit > 0 ? limit : 50)
                .forEach(p -> matches.add(base.relativize(p).toString()));
        }
        return ToolResult.ok(String.join("\n", matches));
    }

    private ToolResult searchContent(Path base, String pattern, String fileGlob, int limit, int offset, int context) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.fail("pattern is required for content search");
        }
        Pattern regex = Pattern.compile(pattern);
        List<String> matches = new ArrayList<>();
        int[] skipped = {0};
        int[] found = {0};
        int ctx = Math.max(0, context);

        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && (fileGlob == null || fileGlob.isBlank() || p.getFileName().toString().matches(globToRegex(fileGlob))))
                .iterator();
            outer:
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        found[0]++;
                        if (found[0] <= offset) {
                            continue;
                        }
                        if (matches.size() >= (limit > 0 ? limit : 50)) {
                            skipped[0]++;
                            continue;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(base.relativize(file).toString()).append(":").append(i + 1).append("|");
                        int start = Math.max(0, i - ctx);
                        int end = Math.min(lines.size(), i + ctx + 1);
                        for (int j = start; j < end; j++) {
                            if (j > start) sb.append("\n");
                            sb.append(j + 1).append("|").append(lines.get(j));
                        }
                        matches.add(sb.toString());
                    }
                }
            }
        }

        String result = String.join("\n---\n", matches);
        if (skipped[0] > 0) {
            result += "\n\n[Hint: Results truncated. Use offset=" + (offset + matches.size()) + " to see more, or narrow with a more specific pattern or file_glob.]";
        }
        // p8: Zero-match hint — when no matches found, probe case-insensitively.
        // If case-insensitive finds matches, append a helpful hint.
        if (found[0] == 0) {
            int ciCount = countCaseInsensitiveMatches(base, pattern, fileGlob);
            if (ciCount > 0) {
                result += "\n[hint: 0 case-sensitive matches, but " + ciCount + " case-insensitive matches found — try with case-insensitive flag]";
            }
        }
        return ToolResult.ok(result);
    }

    private ToolResult countMatches(Path base, String pattern, String fileGlob) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.fail("pattern is required for count search");
        }
        Pattern regex = Pattern.compile(pattern);
        List<String> counts = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && (fileGlob == null || fileGlob.isBlank() || p.getFileName().toString().matches(globToRegex(fileGlob))))
                .iterator();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int matchCount = 0;
                for (String line : lines) {
                    if (regex.matcher(line).find()) {
                        matchCount++;
                    }
                }
                if (matchCount > 0) {
                    counts.add(base.relativize(file).toString() + ": " + matchCount);
                }
            }
        }

        if (counts.isEmpty()) {
            return ToolResult.ok("No matches found.");
        }
        return ToolResult.ok(String.join("\n", counts));
    }

    /**
     * p8: Count case-insensitive matches for the zero-match hint.
     * Uses the same pattern but with CASE_INSENSITIVE flag.
     */
    private int countCaseInsensitiveMatches(Path base, String pattern, String fileGlob) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        Pattern ciRegex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        int count = 0;
        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && (fileGlob == null || fileGlob.isBlank() || p.getFileName().toString().matches(globToRegex(fileGlob))))
                .iterator();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (ciRegex.matcher(line).find()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append("."); break;
                case '.': sb.append("\\."); break;
                default: sb.append(Pattern.quote(String.valueOf(c))); break;
            }
        }
        return sb.toString();
    }

    public record SearchArgs(
        @ToolParam(description = "'content' for regex search inside files or 'files' for file name search") String target,
        @ToolParam(description = "regex pattern for content search or glob for file search") String pattern,
        @ToolParam(description = "directory to search in", required = false) String path,
        @ToolParam(description = "glob filter for content search files", required = false) String fileGlob,
        @ToolParam(description = "output mode: 'content' (default), 'files_only' (file paths), or 'count' (match counts per file)", required = false) @com.fasterxml.jackson.annotation.JsonProperty("output_mode") String outputMode,
        @ToolParam(description = "max results", required = false) int limit,
        @ToolParam(description = "skip first N results", required = false) int offset,
        @ToolParam(description = "context lines around match", required = false) int context
    ) {}
}
