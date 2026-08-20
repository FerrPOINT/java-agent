package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                } else {
                    byte[] bytes = Files.readAllBytes(path);
                    // Limit to 50KB
                    if (bytes.length > 50_000) {
                        content = new String(bytes, 0, 50_000, StandardCharsets.UTF_8)
                            + "\n... [truncated, " + bytes.length + " bytes total]";
                    } else {
                        content = new String(bytes, StandardCharsets.UTF_8);
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
            } catch (Exception e) {
                content = "[Error fetching " + url + ": " + e.getMessage() + "]";
            }
            if (content == null || content.isBlank()) {
                content = "[No content from " + url + "]";
            }
            // Limit content to 50KB
            if (content.length() > 50_000) {
                content = content.substring(0, 50_000) + "\n... [truncated]";
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

    /**
     * m28: fetch URL via JDK HttpClient instead of shelling out to curl —
     * no external process, no shell-injection surface, same 10s timeout.
     */
    private String fetchUrl(String urlString) throws IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(urlString))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET()
            .build();
        java.net.http.HttpResponse<String> response =
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        return response.body().strip();
    }
}