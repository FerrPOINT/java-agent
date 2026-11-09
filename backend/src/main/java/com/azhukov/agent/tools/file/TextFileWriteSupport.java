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
import com.azhukov.agent.core.security.SecureFileAccess;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Set;
import java.util.UUID;

final class TextFileWriteSupport {

    private static final ObjectMapper JSON = SharedObjectMapper.get();
    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextFileWriteSupport() {
    }

    static WriteOutcome write(Path path, String content) throws IOException {
        validateCandidate(path, content);
        try (var parent = SecureFileAccess.openOrCreateDirectory(path.getParent())) {
            return writeValidated(parent, path, null, content);
        }
    }

    static WriteOutcome write(SecureFileAccess.Directory parent, Path path, String expected, String content) throws IOException {
        validateCandidate(path, content);
        return writeValidated(parent, path, expected, content);
    }

    private static WriteOutcome writeValidated(SecureFileAccess.Directory parent, Path path, String expected,
                                               String content) throws IOException {
        var entries = parent.entries();
        Path name = path.getFileName();
        byte[] existing = readExistingBytes(parent, name);
        if (expected != null && (existing == null || !expected.equals(decode(existing)))) {
            throw new IOException("File changed while preparing patch: " + path);
        }
        String adjusted = preserveExistingEncodingShape(existing, content);
        byte[] bytes = adjusted.getBytes(StandardCharsets.UTF_8);
        Path temp = Path.of(".java-agent-write-" + UUID.randomUUID() + ".tmp");
        boolean created = false;
        try {
            try (var channel = entries.newByteChannel(temp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
                created = true;
                if (existing != null) copyExistingPermissions(entries, name, temp);
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            if (expected != null && !Arrays.equals(existing, readExistingBytes(parent, name))) {
                throw new IOException("File changed before applying patch: " + path);
            }
            entries.move(temp, entries, name);
            created = false;
            if (!Arrays.equals(bytes, readExistingBytes(parent, name))) {
                throw new IOException("Post-write verification failed for " + path + ": re-read it before retrying.");
            }
            return new WriteOutcome(path, adjusted, bytes.length, true);
        } catch (IOException | RuntimeException error) {
            if (created) {
                try {
                    entries.deleteFile(temp);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }

    static String read(SecureFileAccess.Directory parent, Path name) throws IOException {
        byte[] bytes = readExistingBytes(parent, name);
        if (bytes == null) throw new NoSuchFileException(name.toString());
        return decode(bytes);
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
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

    private static byte[] readExistingBytes(SecureFileAccess.Directory parent, Path name) throws IOException {
        try (var channel = parent.openRead(name)) {
            return Channels.newInputStream(channel).readAllBytes();
        } catch (NoSuchFileException missing) {
            return null;
        }
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

    private static void copyExistingPermissions(SecureDirectoryStream<Path> parent, Path name, Path temp) throws IOException {
        var original = parent.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (original != null) {
            parent.getFileAttributeView(temp, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setPermissions(original.readAttributes().permissions());
        }
    }

    record WriteOutcome(Path path, String content, int bytesWritten, boolean verified) {
    }
}
