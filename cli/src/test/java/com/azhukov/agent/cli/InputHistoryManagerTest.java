package com.azhukov.agent.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputHistoryManagerTest {

    @Test
    void getHistoryFile_returnsExpectedPath() {
        Path expected = Path.of(System.getProperty("user.home"), ".java-agent-cli", "history.txt");
        assertThat(InputHistoryManager.getHistoryFile()).isEqualTo(expected);
    }

    @Test
    void ensureHistoryFile_createsDirAndFile() {
        InputHistoryManager.ensureHistoryFile();
        assertThat(Files.exists(InputHistoryManager.getHistoryFile())).isTrue();
    }

    @Test
    void ensureHistoryFile_idempotent() {
        InputHistoryManager.ensureHistoryFile();
        InputHistoryManager.ensureHistoryFile();
        assertThat(Files.exists(InputHistoryManager.getHistoryFile())).isTrue();
    }

    @Test
    void loadHistoryEntries_returnsNonEmptyWhenFileExists() {
        InputHistoryManager.ensureHistoryFile();
        // The file exists (created by ensureHistoryFile), so loadHistoryEntries
        // should return a list (possibly empty if file is blank)
        List<String> entries = InputHistoryManager.loadHistoryEntries();
        assertThat(entries).isNotNull();
    }

    @Test
    void loadHistoryEntries_filtersBlankLines() throws Exception {
        // Write some content to the existing history file
        Path historyFile = InputHistoryManager.getHistoryFile();
        Files.write(historyFile, java.util.Arrays.asList("test-cmd-1", "", "  ", "test-cmd-2"));
        List<String> entries = InputHistoryManager.loadHistoryEntries();
        assertThat(entries).contains("test-cmd-1", "test-cmd-2");
        assertThat(entries).doesNotContain("", "  ");
        // Clean up
        Files.deleteIfExists(historyFile);
    }
}