package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;
import org.jline.reader.History;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P1-10: Input history persistence.
 * <p>
 * Configures JLine's FileHistory to persist input history to
 * {@code ~/.java-agent-cli/history.txt}.
 */
@Slf4j
public class InputHistoryManager {

    private static final Path HISTORY_DIR = Path.of(System.getProperty("user.home"), ".java-agent-cli");
    private static final Path HISTORY_FILE = HISTORY_DIR.resolve("history.txt");

    /**
     * Get the history file path.
     */
    public static Path getHistoryFile() {
        return HISTORY_FILE;
    }

    /**
     * Ensure the history directory and file exist.
     */
    public static void ensureHistoryFile() {
        try {
            Files.createDirectories(HISTORY_DIR);
            if (!Files.exists(HISTORY_FILE)) {
                Files.createFile(HISTORY_FILE);
            }
        } catch (IOException e) {
            log.warn("Failed to create history file: {}", e.getMessage());
        }
    }

    /**
     * Attach file-based history to a LineReader.
     * Uses JLine's built-in DefaultHistory which reads/writes a file.
     *
     * @param reader the LineReader to attach history to
     */
    public static void attachHistory(LineReader reader) {
        try {
            ensureHistoryFile();
            reader.setVariable(LineReader.HISTORY_FILE, HISTORY_FILE.toString());
            // Enable history persistence
            reader.setVariable(LineReader.DISABLE_HISTORY, "false");
            // Set max history size
            reader.setVariable(LineReader.HISTORY_SIZE, "1000");
            // Don't add duplicate consecutive entries
            reader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
            // Trim whitespace from history entries
            reader.unsetOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
        } catch (Exception e) {
            log.warn("Failed to configure history: {}", e.getMessage());
        }
    }

    /**
     * Read all history entries from the history file.
     *
     * @return list of history entries (oldest first)
     */
    public static java.util.List<String> loadHistoryEntries() {
        try {
            if (!Files.exists(HISTORY_FILE)) {
                return java.util.Collections.emptyList();
            }
            return Files.readAllLines(HISTORY_FILE).stream()
                .filter(line -> !line.isBlank())
                .toList();
        } catch (IOException e) {
            log.warn("Failed to read history file: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Append an entry to the history file.
     *
     * @param entry the input to append
     */
    public static void appendEntry(String entry) {
        if (entry == null || entry.isBlank()) return;
        try {
            ensureHistoryFile();
            Files.writeString(HISTORY_FILE, entry + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append history entry: {}", e.getMessage());
        }
    }

    /**
     * Clear the history file.
     */
    public static void clearHistory() {
        try {
            Files.writeString(HISTORY_FILE, "");
        } catch (IOException e) {
            log.warn("Failed to clear history: {}", e.getMessage());
        }
    }
}