package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 5: Subdirectory hints (AGENTS.md discovery) test.
 * Verifies that hint files in subdirectories are discovered and formatted correctly.
 */
class SubdirectoryHintsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversAgentsMdInSubdirectory() throws IOException {
        // Setup: working dir with a subdirectory containing AGENTS.md
        Path subdir = tempDir.resolve("backend/src");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("AGENTS.md"), "# Backend Guide\nBuild with gradle.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        // Simulate a read_file tool call targeting a file in the subdir
        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("Main.java").toString()));

        assertThat(hints).isNotNull();
        assertThat(hints).contains("AGENTS.md");
        assertThat(hints).contains("Backend Guide");
    }

    @Test
    void discoversClaudeMd() throws IOException {
        Path subdir = tempDir.resolve("frontend");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("CLAUDE.md"), "# Frontend Rules\nUse TypeScript.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("App.tsx").toString()));

        assertThat(hints).isNotNull();
        assertThat(hints).contains("CLAUDE.md");
        assertThat(hints).contains("Frontend Rules");
    }

    @Test
    void discoversCursorRules() throws IOException {
        Path subdir = tempDir.resolve("project");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve(".cursorrules"), "Always use functional components.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("index.js").toString()));

        assertThat(hints).isNotNull();
        assertThat(hints).contains(".cursorrules");
    }

    @Test
    void noHintsWhenNoFilesPresent() throws IOException {
        Path subdir = tempDir.resolve("empty-dir");
        Files.createDirectories(subdir);

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("file.txt").toString()));

        assertThat(hints).isNull();
    }

    @Test
    void doesNotReloadAlreadyLoadedDir() throws IOException {
        Path subdir = tempDir.resolve("module");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("AGENTS.md"), "Module guide.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        // First call should discover the hint
        String hints1 = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("file1.java").toString()));
        assertThat(hints1).isNotNull();

        // Second call to same dir should not re-discover (already loaded)
        String hints2 = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("file2.java").toString()));
        assertThat(hints2).isNull();
    }

    @Test
    void rejectsPathsOutsideWorkingDir() throws IOException {
        // Create an AGENTS.md outside the working dir
        Path outsideDir = tempDir.resolve("../outside");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("AGENTS.md"), "Outside guide.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", outsideDir.resolve("file.java").toString()));

        assertThat(hints).isNull();
    }

    @Test
    void extractsPathsFromTerminalCommand() throws IOException {
        Path subdir = tempDir.resolve("scripts");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("AGENTS.md"), "Scripts guide.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("terminal",
            Map.of("command", "ls " + subdir + "/script.sh"));

        assertThat(hints).isNotNull();
        assertThat(hints).contains("AGENTS.md");
    }

    @Test
    void firstMatchWinsPerDirectory() throws IOException {
        Path subdir = tempDir.resolve("mixed");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("AGENTS.md"), "First file.");
        Files.writeString(subdir.resolve("CLAUDE.md"), "Second file.");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("code.java").toString()));

        assertThat(hints).isNotNull();
        // AGENTS.md should be found first (priority order in HINT_FILENAMES)
        assertThat(hints).contains("AGENTS.md");
        assertThat(hints).doesNotContain("CLAUDE.md");
    }

    @Test
    void emptyHintFileIsIgnored() throws IOException {
        Path subdir = tempDir.resolve("empty-hint");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("AGENTS.md"), "");

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("code.java").toString()));

        assertThat(hints).isNull();
    }

    @Test
    void truncatesLargeHintFile() throws IOException {
        Path subdir = tempDir.resolve("large-hint");
        Files.createDirectories(subdir);
        String largeContent = "A".repeat(10_000);
        Files.writeString(subdir.resolve("AGENTS.md"), largeContent);

        SubdirectoryHintsService service = new SubdirectoryHintsService(tempDir);

        String hints = service.checkToolCall("read_file",
            Map.of("path", subdir.resolve("code.java").toString()));

        assertThat(hints).isNotNull();
        assertThat(hints).contains("truncated");
    }
}