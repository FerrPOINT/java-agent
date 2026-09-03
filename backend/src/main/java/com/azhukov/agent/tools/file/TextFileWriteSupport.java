package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.SharedObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class TextFileWriteSupport {

    private static final ObjectMapper JSON = SharedObjectMapper.get();
    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextFileWriteSupport() {
    }

    static WriteOutcome write(Path requestedPath, String content) throws IOException {
        validateCandidate(requestedPath, content);

        Path target = FileToolSafety.resolveWritableTarget(requestedPath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        byte[] existingBytes = readExistingBytes(target);
        String adjusted = preserveExistingEncodingShape(existingBytes, content);
        byte[] bytes = adjusted.getBytes(StandardCharsets.UTF_8);

        Path temp = Files.createTempFile(parent, ".java-agent-write-", ".tmp");
        try {
            copyExistingPermissions(target, temp);
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            moveReplacing(temp, target);
            verifyBytesPersisted(target, bytes);
            return new WriteOutcome(target, adjusted, bytes.length, true);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best-effort cleanup after a failed write.
            }
            throw e;
        }
    }

    static void validateCandidate(Path requestedPath, String content) throws IOException {
        validateText(content);
        validateCandidateSyntax(requestedPath, content);
    }

    private static void validateCandidateSyntax(Path requestedPath, String content) throws IOException {
        String ext = extensionOf(requestedPath);
        try {
            if (".json".equals(ext)) {
                JSON.readTree(content);
                return;
            }
            if (".yaml".equals(ext) || ".yml".equals(ext)) {
                validateYaml(content);
                return;
            }
            if (".toml".equals(ext)) {
                validateToml(content);
            }
        } catch (JsonProcessingException | YAMLException | IllegalArgumentException e) {
            throw syntaxFailure(requestedPath, ext, e);
        }
    }

    private static void validateYaml(String content) {
        Parser parser = new ParserImpl(new StreamReader(content), new LoaderOptions());
        while (true) {
            Event event = parser.getEvent();
            if (event == null || event.is(Event.ID.StreamEnd)) {
                return;
            }
        }
    }

    private static void validateToml(String content) {
        TomlParseResult result = Toml.parse(content);
        if (result.hasErrors()) {
            throw new IllegalArgumentException(result.errors().get(0).toString());
        }
    }

    private static IOException syntaxFailure(Path requestedPath, String ext, Exception cause) {
        return new IOException(
            "Refusing to write '" + requestedPath + "': candidate content fails " + ext
                + " syntax validation (" + concise(cause) + "). The file was NOT created or modified. "
                + "Fix the content and retry.",
            cause);
    }

    private static String extensionOf(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        String flattened = message.replaceAll("\\s+", " ").strip();
        if (flattened.length() <= 300) {
            return flattened;
        }
        return flattened.substring(0, 297) + "...";
    }

    private static byte[] readExistingBytes(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Target is not a regular file: " + target);
        }
        return Files.readAllBytes(target);
    }

    private static String preserveExistingEncodingShape(byte[] existingBytes, String content) {
        if (existingBytes == null || existingBytes.length == 0) {
            return content;
        }

        String adjusted = content;
        String lineEnding = detectLineEnding(existingBytes);
        if (lineEnding != null) {
            adjusted = normalizeLineEndings(adjusted, lineEnding);
        }
        if (hasUtf8Bom(existingBytes) && !adjusted.startsWith("\uFEFF")) {
            adjusted = "\uFEFF" + adjusted;
        }
        return adjusted;
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
            && bytes[0] == UTF8_BOM[0]
            && bytes[1] == UTF8_BOM[1]
            && bytes[2] == UTF8_BOM[2];
    }

    private static String detectLineEnding(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r') {
                if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    return "\r\n";
                }
                return "\r";
            }
            if (bytes[i] == '\n') {
                return "\n";
            }
        }
        return null;
    }

    private static String normalizeLineEndings(String content, String targetLineEnding) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        if ("\n".equals(targetLineEnding)) {
            return normalized;
        }
        return normalized.replace("\n", targetLineEnding);
    }

    private static void validateText(String content) throws IOException {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < content.length() && Character.isLowSurrogate(content.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw new IOException("Content contains an unpaired Unicode surrogate at index " + i);
            }
            if (Character.isLowSurrogate(c)) {
                throw new IOException("Content contains an unpaired Unicode surrogate at index " + i);
            }
        }
    }

    private static void copyExistingPermissions(Path target, Path temp) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some file systems do not support POSIX permissions.
        }
    }

    private static void moveReplacing(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyBytesPersisted(Path target, byte[] expected) throws IOException {
        byte[] actual = Files.readAllBytes(target);
        if (!Arrays.equals(expected, actual)) {
            throw new IOException(
                "Post-write verification failed for " + target
                    + ": on-disk content differs from the intended write. The file may be corrupt; "
                    + "re-read it before retrying.");
        }
    }

    record WriteOutcome(Path path, String content, int bytesWritten, boolean verified) {
    }
}
