package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1-7: @ context reference expander.
 * <p>
 * Expands @-prefixed context references in user input before sending to backend:
 * <ul>
 *   <li>{@code @diff} — expands to the output of {@code git diff}</li>
 *   <li>{@code @staged} — expands to {@code git diff --cached --stat}</li>
 *   <li>{@code @git} — expands to {@code git log --oneline -10}</li>
 *   <li>{@code @file:path} — reads file content and includes it</li>
 *   <li>{@code @folder:path} — lists directory contents</li>
 *   <li>{@code @url:url} — fetches URL content (basic HTTP GET)</li>
 * </ul>
 */
@Slf4j
public class ContextReferenceExpander {

    private static final Pattern REF_PATTERN = Pattern.compile(
        "@(diff|staged|git|file:|folder:|url:)");

    private static final int CONTENT_LIMIT = 50_000;
    private static final int URL_READ_LIMIT = 4 * CONTENT_LIMIT;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final Duration urlTimeout;

    public ContextReferenceExpander() {
        this(Duration.ofSeconds(10));
    }

    ContextReferenceExpander(Duration urlTimeout) {
        this.urlTimeout = urlTimeout;
    }

    /**
     * Expand all @-references in the input text.
     *
     * @param input the user's input text, possibly containing @-references
     * @return the expanded text with references replaced by their content
     */
    public String expand(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // Expand @diff
        input = expandRef(input, "@diff", () -> safeRunCommand("git diff"));

        // Expand @staged
        input = expandRef(input, "@staged", () -> safeRunCommand("git diff --cached --stat"));

        // Expand @git
        input = expandRef(input, "@git", () -> safeRunCommand("git log --oneline -10"));

        // Expand @file:path
        input = expandFileRef(input);

        // Expand @folder:path
        input = expandFolderRef(input);

        // Expand @url:url
        input = expandUrlRef(input);

        return input;
    }

    /**
     * Check if input contains any @-references.
     */
    public static boolean hasReferences(String input) {
        if (input == null || input.isEmpty()) return false;
        return REF_PATTERN.matcher(input).find();
    }

    private String expandRef(String input, String ref, java.util.function.Supplier<String> expander) {
        if (!input.contains(ref)) {
            return input;
        }
        String content;
        try {
            content = expander.get();
        } catch (RuntimeException e) {
            log.warn("Failed to expand {}: {}", ref, e.getMessage());
            content = "[Error expanding " + ref + ": " + e.getMessage() + "]";
        } catch (Exception e) {
            log.warn("Failed to expand {}: {}", ref, e.getMessage());
            content = "[Error expanding " + ref + ": " + e.getMessage() + "]";
        }
        if (content == null || content.isBlank()) {
            content = "[No content for " + ref + "]";
        }
        return input.replace(ref, "\n--- " + ref + " ---\n" + content + "\n--- end ---\n");
    }

    private String expandFileRef(String input) {
        Pattern p = Pattern.compile("@file:(\\S+)");
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String filePath = m.group(1);
            String content;
            try {
                Path path = Path.of(filePath);
                if (!Files.exists(path)) {
                    content = "[File not found: " + filePath + "]";
                } else if (Files.isDirectory(path)) {
                    content = "[Path is a directory, use @folder: instead: " + filePath + "]";
                } else if (!Files.isRegularFile(path)) {
                    content = "[Path is not a regular file: " + filePath + "]";
                } else {
                    try (var file = Files.newInputStream(path)) {
                        byte[] bytes = file.readNBytes(CONTENT_LIMIT + 1);
                        content = decodePrefix(bytes, CONTENT_LIMIT, StandardCharsets.UTF_8);
                        if (bytes.length > CONTENT_LIMIT) {
                            content += "\n... [truncated]";
                        }
                    }
                }
            } catch (IOException e) {
                content = "[Error reading " + filePath + ": " + e.getMessage() + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                "\n--- @file:" + filePath + " ---\n" + content + "\n--- end ---\n"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String expandFolderRef(String input) {
        Pattern p = Pattern.compile("@folder:(\\S+)");
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String folderPath = m.group(1);
            String content;
            try {
                Path path = Path.of(folderPath);
                if (!Files.exists(path)) {
                    content = "[Folder not found: " + folderPath + "]";
                } else if (!Files.isDirectory(path)) {
                    content = "[Path is a file, use @file: instead: " + folderPath + "]";
                } else {
                    StringBuilder listing = new StringBuilder();
                    try (var stream = Files.list(path)) {
                        stream.sorted()
                            .forEachOrdered(p2 -> {
                                String name = p2.getFileName().toString();
                                String type = Files.isDirectory(p2) ? "[dir]" : "[file]";
                                listing.append(String.format("  %s %s%n", type, name));
                            });
                    }
                    content = listing.toString().trim();
                }
            } catch (IOException e) {
                content = "[Error listing " + folderPath + ": " + e.getMessage() + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                "\n--- @folder:" + folderPath + " ---\n" + content + "\n--- end ---\n"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String expandUrlRef(String input) {
        Pattern p = Pattern.compile("@url:(\\S+)");
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = m.group(1);
            String content;
            try {
                content = fetchUrl(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                content = "[Error fetching " + url + ": interrupted]";
            } catch (Exception e) {
                content = "[Error fetching " + url + ": " + e.getMessage() + "]";
            }
            if (content == null || content.isBlank()) {
                content = "[No content from " + url + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                "\n--- @url:" + url + " ---\n" + content + "\n--- end ---\n"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String runCommand(String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0 && output.isBlank()) {
            return "[Command failed with exit code " + exitCode + "]";
        }
        return output.strip();
    }

    /**
     * Safe wrapper for runCommand that catches checked exceptions and returns error messages.
     */
    private String safeRunCommand(String command) {
        try {
            return runCommand(command);
        } catch (IOException | InterruptedException e) {
            return "[Error running '" + command + "': " + e.getMessage() + "]";
        }
    }

    private String fetchUrl(String urlString) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(urlString)).timeout(urlTimeout).GET().build();
        record UrlBody(byte[] bytes, Charset charset) {}
        var fetch = new FutureTask<>(() -> {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                return new UrlBody(body.readNBytes(URL_READ_LIMIT + 1), responseCharset(response));
            }
        });
        Thread.ofVirtual().name("cli-context-url").start(fetch);
        UrlBody body;
        try {
            body = fetch.get(urlTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new HttpTimeoutException("request timed out");
        } catch (ExecutionException e) {
            throw new IOException("could not read response", e.getCause());
        } finally {
            fetch.cancel(true);
        }
        String content = decodePrefix(body.bytes(), URL_READ_LIMIT, body.charset()).strip();
        if (content.length() > CONTENT_LIMIT) {
            int end = Character.isHighSurrogate(content.charAt(CONTENT_LIMIT - 1))
                ? CONTENT_LIMIT - 1 : CONTENT_LIMIT;
            return content.substring(0, end) + "\n... [truncated]";
        }
        return body.bytes().length > URL_READ_LIMIT ? content + "\n... [truncated]" : content;
    }

    private static Charset responseCharset(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        if (contentType != null) {
            try {
                Charset charset = MediaType.parseMediaType(contentType).getCharset();
                if (charset != null) return charset;
            } catch (IllegalArgumentException ignored) {
                // As with the default HTTP string handler, a malformed charset falls back to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String decodePrefix(byte[] bytes, int limit, Charset charset) {
        if (bytes.length <= limit) return new String(bytes, charset);
        var decoder = charset.newDecoder();
        var output = CharBuffer.allocate((int) Math.ceil(limit * decoder.maxCharsPerByte()));
        decoder
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, 0, limit), output, false);
        return output.flip().toString();
    }

}