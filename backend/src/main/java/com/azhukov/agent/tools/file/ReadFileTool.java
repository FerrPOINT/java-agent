package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Locale;

@Component
@AgentTool(
    name = "read_file",
    description = "Read a text file with line numbers and pagination. Use this instead of cat/head/tail in terminal. Output format: 'LINE_NUM|CONTENT'. Suggests similar filenames if not found. Use offset and limit for large files. Reads exceeding ~100K characters are truncated on a line boundary and return a next_offset; continue with offset to read the rest. NOTE: Cannot read images or other binary files — use vision_analyze for images.",
    toolset = "file"
)
@RequiredArgsConstructor
public class ReadFileTool implements ToolHandler {

    private static final int MAX_READ_CHARS = 100_000;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".svg",
        ".pdf", ".zip", ".gz", ".tar", ".jar", ".class", ".so", ".dll",
        ".exe", ".bin", ".dat", ".db", ".sqlite", ".mp3", ".mp4", ".avi",
        ".mov", ".wav", ".ogg", ".flac", ".iso", ".img", ".wasm", ".o", ".a"
    );

    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        // Infinite output — never reach EOF
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        // Blocks waiting for input
        "/dev/stdin", "/dev/tty", "/dev/console",
        // Nonsensical to read
        "/dev/stdout", "/dev/stderr",
        // fd aliases
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2",
        // Legacy entries kept from the original java set
        "/dev/null", "/dev/tcp"
    );

    private final AgentProperties properties;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ReadFileArgs args = ToolHandler.parseJson(arguments, ReadFileArgs.class);
        String rawPath = args.path();
        Path path = Path.of(rawPath).toAbsolutePath().normalize();

        // Device path blocking — check before anything else
        if (isBlockedDevicePath(rawPath)) {
            return ToolResult.fail("Device file blocked: " + rawPath);
        }

        if (!isPathAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories: " + rawPath);
        }

        if (!Files.exists(path)) {
            return ToolResult.fail("File not found: " + rawPath);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("Not a file: " + rawPath);
        }

        // Binary file detection by extension
        if (isBinaryFile(path)) {
            return ToolResult.fail("Cannot read binary file: " + rawPath + ". Use vision_analyze for images.");
        }

        try {
            // h55: Detect UTF-16 BOM and transcode to UTF-8 before processing.
            byte[] rawBytes = Files.readAllBytes(path);
            String content = transcodeUtf16IfBom(rawBytes);

            List<String> lines = content.isEmpty() ? List.of() : List.of(content.split("\n", -1));
            int offset = Math.max(1, args.offset());
            int start = offset - 1;
            int limit = args.limit() > 0 ? args.limit()
                // Feature 7: operator-configurable default (agent.tool-output.read-file-max-lines)
                : (properties.getToolOutput().getReadFileMaxLines() > 0
                    ? properties.getToolOutput().getReadFileMaxLines() : 2000);
            int end = Math.min(lines.size(), start + limit);

            if (start >= lines.size()) {
                return ToolResult.ok("");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
            }

            // Remove trailing newline
            String result = sb.toString();
            if (result.endsWith("\n")) {
                result = result.substring(0, result.length() - 1);
            }

            // p10: Truncation UX — when output is truncated by limit, show remaining lines count.
            if (end < lines.size()) {
                int shown = end - start;
                int remaining = lines.size() - end;
                result += "\n[truncated: showing lines " + offset + "-" + (start + shown) + " of " + lines.size() + " total, " + remaining + " remaining]";
            }

            // Char cap truncation — Hermes parity: return next_offset so the
            // model can continue reading from where it left off.
            if (result.length() > MAX_READ_CHARS) {
                // Find the last complete line boundary within MAX_READ_CHARS
                int cutAt = MAX_READ_CHARS;
                // Walk back to the last newline so we don't split a line
                int lastNewline = result.lastIndexOf('\n', cutAt - 1);
                if (lastNewline > MAX_READ_CHARS / 2) {
                    cutAt = lastNewline;
                }
                String truncated = result.substring(0, cutAt);
                // Count how many lines were included to compute next_offset
                int linesShown = countLines(truncated);
                int nextOffset = offset + linesShown;
                result = truncated
                    + "\n[... file truncated at ~" + cutAt + " chars. "
                    + "Use offset=" + nextOffset + " to continue reading.]";
            }
            return ToolResult.ok(result);
        } catch (IOException e) {
            return ToolResult.fail("Failed to read file: " + e.getMessage());
        }
    }

    /** Count the number of newline-separated lines in a string. */
    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * h55: Detect UTF-16 BOM (FF FE for UTF-16LE or FE FF for UTF-16BE)
     * and transcode the raw bytes to UTF-8. If no BOM is found, decode as UTF-8.
     */
    private static String transcodeUtf16IfBom(byte[] rawBytes) {
        if (rawBytes.length >= 2) {
            int b0 = rawBytes[0] & 0xFF;
            int b1 = rawBytes[1] & 0xFF;
            // UTF-16LE BOM: FF FE
            if (b0 == 0xFF && b1 == 0xFE) {
                return new String(rawBytes, 2, rawBytes.length - 2, StandardCharsets.UTF_16LE);
            }
            // UTF-16BE BOM: FE FF
            if (b0 == 0xFE && b1 == 0xFF) {
                return new String(rawBytes, 2, rawBytes.length - 2, StandardCharsets.UTF_16BE);
            }
        }
        return new String(rawBytes, StandardCharsets.UTF_8);
    }

    private boolean isBinaryFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIndex);
        return BINARY_EXTENSIONS.contains(ext);
    }

    private boolean isBlockedDevicePath(String rawPath) {
        String normalized = Path.of(rawPath).toAbsolutePath().normalize().toString();
        for (String device : BLOCKED_DEVICE_PATHS) {
            if (normalized.equals(device) || normalized.startsWith(device + "/")) {
                return true;
            }
        }
        return false;
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

    public record ReadFileArgs(
        @ToolParam(description = "absolute or relative path to the file") String path,
        @ToolParam(description = "starting line number (1-based)", required = false) int offset,
        @ToolParam(description = "maximum number of lines to read", required = false) int limit
    ) {}
}