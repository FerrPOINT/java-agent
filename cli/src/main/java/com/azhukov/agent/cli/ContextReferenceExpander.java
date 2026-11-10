package com.azhukov.agent.cli;

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
public class ContextReferenceExpander {

    private static final Pattern REF_PATTERN = Pattern.compile(
        "@(?:(diff|staged|git)\\b|(file|folder|url):(\\S+))");

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

        Matcher references = REF_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        var gitOutputs = new java.util.HashMap<String, String>();
        while (references.find()) {
            String command = references.group(1);
            String value = references.group(3);
            String content = command != null ? gitOutputs.computeIfAbsent(command, this::gitContext)
                : switch (references.group(2)) {
                    case "file" -> fileContext(value);
                    case "folder" -> folderContext(value);
                    case "url" -> urlContext(value);
                    default -> throw new IllegalStateException("Unknown context reference");
                };
            references.appendReplacement(result, Matcher.quoteReplacement(
                "\n--- " + references.group() + " ---\n" + content + "\n--- end ---\n"));
        }
        return references.appendTail(result).toString();
    }

    public static boolean hasReferences(String input) {
        return input != null && REF_PATTERN.matcher(input).find();
    }

    private String fileContext(String name) {
        try {
            Path path = Path.of(name);
            if (!Files.exists(path)) return "[File not found: " + name + "]";
            if (Files.isDirectory(path)) return "[Path is a directory, use @folder: instead: " + name + "]";
            if (!Files.isRegularFile(path)) return "[Path is not a regular file: " + name + "]";
            try (var input = Files.newInputStream(path)) {
                return limitedUtf8(input);
            }
        } catch (IOException | IllegalArgumentException e) {
            return "[Error reading " + name + ": " + e.getMessage() + "]";
        }
    }

    private String folderContext(String name) {
        try {
            Path path = Path.of(name);
            if (!Files.exists(path)) return "[Folder not found: " + name + "]";
            if (!Files.isDirectory(path)) return "[Path is a file, use @file: instead: " + name + "]";
            try (var entries = Files.list(path)) {
                var paths = entries.limit(513).toList();
                StringBuilder listing = new StringBuilder();
                for (Path child : paths.stream().limit(512).sorted().toList()) {
                    String line = (Files.isDirectory(child) ? "  [dir] " : "  [file] ") + child.getFileName() + "\n";
                    if (listing.length() + line.length() > CONTENT_LIMIT) return listing + "... [truncated]";
                    listing.append(line);
                }
                return listing.toString().trim() + (paths.size() > 512 ? "\n... [truncated]" : "");
            }
        } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException e) {
            return "[Error listing " + name + ": " + e.getMessage() + "]";
        }
    }

    private String urlContext(String url) {
        try {
            String content = fetchUrl(url);
            return content.isBlank() ? "[No content from " + url + "]" : content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[Error fetching " + url + ": interrupted]";
        } catch (Exception e) {
            return "[Error fetching " + url + ": " + e.getMessage() + "]";
        }
    }

    private String gitContext(String command) {
        String[] args = switch (command) {
            case "diff" -> new String[]{"git", "--no-pager", "diff", "--no-ext-diff"};
            case "staged" -> new String[]{"git", "--no-pager", "diff", "--no-ext-diff", "--cached", "--stat"};
            case "git" -> new String[]{"git", "--no-pager", "log", "--oneline", "-10"};
            default -> throw new IllegalArgumentException(command);
        };
        Process process;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            return "[Error running @" + command + ": " + e.getMessage() + "]";
        }
        var read = new FutureTask<>(() -> {
            try (var input = process.getInputStream()) {
                String output = limitedUtf8(input).strip();
                if (output.endsWith("... [truncated]")) return output;
                int status = process.waitFor();
                if (!output.isBlank()) return output;
                return status == 0 ? "[No content for @" + command + "]" : "[Command failed with exit code " + status + "]";
            }
        });
        Thread.ofVirtual().name("cli-context-git").start(read);
        try {
            return read.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[Error running @" + command + ": interrupted]";
        } catch (TimeoutException | ExecutionException e) {
            return "[Error running @" + command + ": " + e.getClass().getSimpleName() + "]";
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            read.cancel(true);
        }
    }

    private static String limitedUtf8(java.io.InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(CONTENT_LIMIT + 1);
        return decodePrefix(bytes, CONTENT_LIMIT, StandardCharsets.UTF_8)
            + (bytes.length > CONTENT_LIMIT ? "\n... [truncated]" : "");
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