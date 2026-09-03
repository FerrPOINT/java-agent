package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.FileSafety;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff", ".tif",
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz", ".z", ".tgz", ".iso",
        ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".obj", ".lib",
        ".app", ".msi", ".deb", ".rpm",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp",
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        ".pyc", ".pyo", ".class", ".jar", ".war", ".ear", ".node", ".wasm", ".rlib",
        ".sqlite", ".sqlite3", ".db", ".mdb", ".idx",
        ".psd", ".ai", ".eps", ".sketch", ".fig", ".xd", ".blend", ".3ds", ".max",
        ".swf", ".fla",
        ".lockb", ".dat", ".data"
    );

    private final AgentProperties properties;
    private final FileSafety fileSafety;

    public SearchFilesTool() {
        this(new AgentProperties());
    }

    public SearchFilesTool(AgentProperties properties) {
        this(properties, FileToolSafety.defaultSafety(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SearchFilesTool(AgentProperties properties, FileSafety fileSafety) {
        this.properties = properties;
        this.fileSafety = fileSafety;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args;
        try {
            args = ToolHandler.parseJson(arguments, SearchArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        String target = normalizeTarget(args.target());
        String rawBase = args.path() == null ? "." : args.path();
        Path base = FileToolSafety.resolvePath(rawBase, session);

        ToolResult safetyCheck = FileToolSafety.ensureReadable(properties, fileSafety, base, rawBase);
        if (safetyCheck != null) {
            return jsonFail(safetyCheck.error());
        }

        if (!Files.exists(base)) {
            return jsonFail("Path not found: " + args.path());
        }

        try {
            if ("files".equals(target)) {
                return findFiles(base, args.pattern(), args.limit(), args.offset());
            }
            String outputMode = args.outputMode() == null ? "content" : args.outputMode().toLowerCase();
            if ("count".equals(outputMode)) {
                return countMatches(base, args.pattern(), args.fileGlob());
            }
            if ("files_only".equals(outputMode)) {
                return searchContentFilesOnly(base, args.pattern(), args.fileGlob(), args.limit(), args.offset());
            }
            return searchContent(base, args.pattern(), args.fileGlob(), args.limit(), args.offset(), args.context());
        } catch (PatternSyntaxException e) {
            return jsonFail("Invalid regex pattern: " + e.getMessage());
        } catch (IOException e) {
            return jsonFail("Search failed: " + e.getMessage());
        }
    }

    private static String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            return "content";
        }
        String normalized = target.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "find" -> "files";
            case "grep" -> "content";
            default -> normalized;
        };
    }

    private ToolResult findFiles(Path base, String pattern, int limit, int offset) throws IOException {
        String glob = pattern == null || pattern.isBlank() ? "*" : pattern;
        List<String> matches = new ArrayList<>();
        int maxResults = limit > 0 ? limit : 50;
        int safeOffset = Math.max(0, offset);
        int[] found = {0};
        int[] skipped = {0};
        try (Stream<Path> stream = Files.find(base, Integer.MAX_VALUE,
                (path, attrs) -> attrs.isRegularFile()
                    && matchesGlob(base, path, glob)
                    && FileToolSafety.isReadable(properties, fileSafety, path))) {
            stream.sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                .forEach(p -> {
                    int current = found[0]++;
                    if (current < safeOffset) {
                        return;
                    }
                    if (matches.size() >= maxResults) {
                        skipped[0]++;
                        return;
                    }
                    matches.add(base.relativize(p).toString());
                });
        }
        Map<String, Object> result = searchResult(found[0]);
        if (!matches.isEmpty()) {
            result.put("files", matches);
        }
        if (skipped[0] > 0) {
            result.put("truncated", true);
        }
        return jsonOk(result);
    }

    private ToolResult searchContentFilesOnly(Path base, String pattern, String fileGlob, int limit, int offset) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return jsonFail("pattern is required for content search");
        }
        Pattern regex = Pattern.compile(pattern);
        List<String> matches = new ArrayList<>();
        int maxResults = limit > 0 ? limit : 50;
        int safeOffset = Math.max(0, offset);
        int[] skipped = {0};
        int[] found = {0};

        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && FileToolSafety.isReadable(properties, fileSafety, p)
                && matchesGlob(base, p, fileGlob))
                .iterator();
            for (Path file : files) {
                List<String> lines = readSearchLines(file);
                for (String line : lines) {
                    if (regex.matcher(line).find()) {
                        found[0]++;
                        if (found[0] <= safeOffset) {
                            break;
                        }
                        if (matches.size() >= maxResults) {
                            skipped[0]++;
                            break;
                        }
                        matches.add(base.relativize(file).toString());
                        break;
                    }
                }
            }
        }

        Map<String, Object> result = searchResult(found[0]);
        if (!matches.isEmpty()) {
            result.put("files", matches);
        }
        if (skipped[0] > 0) {
            result.put("truncated", true);
            result.put("hint", "Results truncated. Use offset=" + (safeOffset + matches.size())
                + " to see more, or narrow with a more specific pattern or file_glob.");
        }
        if (found[0] == 0) {
            int ciCount = countCaseInsensitiveMatches(base, pattern, fileGlob);
            if (ciCount > 0) {
                result.put("warning", "0 case-sensitive matches, but " + ciCount
                    + " case-insensitive matches found - try with case-insensitive flag");
            }
        }
        return jsonOk(result);
    }

    private ToolResult searchContent(Path base, String pattern, String fileGlob, int limit, int offset, int context) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return jsonFail("pattern is required for content search");
        }
        Pattern regex = Pattern.compile(pattern);
        List<Map<String, Object>> matches = new ArrayList<>();
        int[] skipped = {0};
        int[] found = {0};
        int ctx = Math.max(0, context);
        int maxResults = limit > 0 ? limit : 50;
        int safeOffset = Math.max(0, offset);

        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && FileToolSafety.isReadable(properties, fileSafety, p)
                && matchesGlob(base, p, fileGlob))
                .iterator();
            outer:
            for (Path file : files) {
                List<String> lines = readSearchLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        found[0]++;
                        if (found[0] <= safeOffset) {
                            continue;
                        }
                        if (matches.size() >= maxResults) {
                            skipped[0]++;
                            continue;
                        }
                        StringBuilder sb = new StringBuilder();
                        int start = Math.max(0, i - ctx);
                        int end = Math.min(lines.size(), i + ctx + 1);
                        for (int j = start; j < end; j++) {
                            if (j > start) sb.append("\n");
                            sb.append(j + 1).append("|").append(lines.get(j));
                        }
                        Map<String, Object> match = new LinkedHashMap<>();
                        match.put("path", base.relativize(file).toString());
                        match.put("line", i + 1);
                        match.put("content", sb.toString());
                        matches.add(match);
                    }
                }
            }
        }

        Map<String, Object> result = searchResult(found[0]);
        if (!matches.isEmpty()) {
            result.put("matches", matches);
        }
        if (skipped[0] > 0) {
            result.put("truncated", true);
            result.put("hint", "Results truncated. Use offset=" + (safeOffset + matches.size())
                + " to see more, or narrow with a more specific pattern or file_glob.");
        }
        // p8: Zero-match hint — when no matches found, probe case-insensitively.
        // If case-insensitive finds matches, append a helpful hint.
        if (found[0] == 0) {
            int ciCount = countCaseInsensitiveMatches(base, pattern, fileGlob);
            if (ciCount > 0) {
                result.put("warning", "0 case-sensitive matches, but " + ciCount
                    + " case-insensitive matches found - try with case-insensitive flag");
            }
        }
        return jsonOk(result);
    }

    private ToolResult countMatches(Path base, String pattern, String fileGlob) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return jsonFail("pattern is required for count search");
        }
        Pattern regex = Pattern.compile(pattern);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;

        try (Stream<Path> stream = Files.walk(base)) {
            Iterable<Path> files = () -> stream.filter(p -> Files.isRegularFile(p)
                && FileToolSafety.isReadable(properties, fileSafety, p)
                && matchesGlob(base, p, fileGlob))
                .iterator();
            for (Path file : files) {
                List<String> lines = readSearchLines(file);
                int matchCount = 0;
                for (String line : lines) {
                    if (regex.matcher(line).find()) {
                        matchCount++;
                    }
                }
                if (matchCount > 0) {
                    counts.put(base.relativize(file).toString(), matchCount);
                    total += matchCount;
                }
            }
        }

        Map<String, Object> result = searchResult(total);
        if (counts.isEmpty()) {
            result.put("message", "No matches found.");
        } else {
            result.put("counts", counts);
        }
        return jsonOk(result);
    }

    private static Map<String, Object> searchResult(int totalCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_count", totalCount);
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
        String message = error == null ? "Search failed" : error;
        result.put("success", false);
        result.put("error", message);
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), message);
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Search failed\"}", message);
        }
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
                && FileToolSafety.isReadable(properties, fileSafety, p)
                && matchesGlob(base, p, fileGlob))
                .iterator();
            for (Path file : files) {
                List<String> lines = readSearchLines(file);
                for (String line : lines) {
                    if (ciRegex.matcher(line).find()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private List<String> readSearchLines(Path file) throws IOException {
        if (hasBinaryExtension(file) || isLikelyBinarySample(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException | UnmappableCharacterException e) {
            return List.of();
        }
    }

    private boolean hasBinaryExtension(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(fileName.substring(dotIndex));
    }

    private boolean isLikelyBinarySample(Path file) throws IOException {
        byte[] sample;
        try (var input = Files.newInputStream(file)) {
            sample = input.readNBytes(8192);
        }
        for (byte b : sample) {
            if (b == 0) {
                return true;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }

    private boolean matchesGlob(Path base, Path path, String glob) {
        if (glob == null || glob.isBlank()) {
            return true;
        }
        String regex = globToRegex(glob.replace('\\', '/'));
        String fileName = path.getFileName().toString();
        String relative = base.relativize(path).toString().replace('\\', '/');
        return fileName.matches(regex) || relative.matches(regex);
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
        @ToolParam(description = "glob filter for content search files", required = false) @JsonProperty("file_glob") @JsonAlias("fileGlob") String fileGlob,
        @ToolParam(description = "output mode: 'content' (default), 'files_only' (file paths), or 'count' (match counts per file)", required = false) @JsonProperty("output_mode") @JsonAlias("outputMode") String outputMode,
        @ToolParam(description = "max results", required = false) int limit,
        @ToolParam(description = "skip first N results", required = false) int offset,
        @ToolParam(description = "context lines around match", required = false) int context
    ) {}
}
