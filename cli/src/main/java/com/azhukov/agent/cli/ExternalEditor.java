package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P1-9: External editor support.
 * <p>
 * Opens $EDITOR (or vi/nano as fallback) on a temp file with the current
 * prompt content, then reads the edited content back into the prompt.
 */
@Slf4j
public class ExternalEditor {

    /**
     * Open the external editor with the given initial content.
     *
     * @param initialContent the current prompt content (may be null/empty)
     * @return the edited content, or null if the editor couldn't be opened
     */
    public static String edit(String initialContent) {
        String editor = System.getenv().getOrDefault("EDITOR", "vi");
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("java-agent-cli-", ".md");
            String content = initialContent != null ? initialContent : "";
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                editor + " " + escapeForShell(tempFile.toString()));
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("Editor exited with code {}", exitCode);
            }

            String edited = Files.readString(tempFile, StandardCharsets.UTF_8);
            // Remove trailing newline that editors often add
            if (edited.endsWith("\n")) {
                edited = edited.substring(0, edited.length() - 1);
            }
            return edited;
        } catch (IOException e) {
            log.error("Failed to open editor: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Editor interrupted");
            return null;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Open the external editor with empty content.
     *
     * @return the edited content, or null if the editor couldn't be opened
     */
    public static String edit() {
        return edit(null);
    }

    /**
     * Escape a file path for safe shell usage.
     */
    private static String escapeForShell(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}