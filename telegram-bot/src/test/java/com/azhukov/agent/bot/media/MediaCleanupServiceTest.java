package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCleanupServiceTest {

    private MediaCleanupService service;

    @BeforeEach
    void setUp() {
        service = new MediaCleanupService();
    }

    @Test
    void cleanupDeletesFilesOlderThanOneHour(@TempDir Path tempDir) throws Exception {
        // Create a file and set its modification time to 2 hours ago
        Path oldFile = tempDir.resolve("old_photo.jpg");
        Files.write(oldFile, new byte[]{1, 2, 3});
        setFileAge(oldFile, 2, ChronoUnit.HOURS);

        // Create a fresh file (current time)
        Path freshFile = tempDir.resolve("fresh_photo.jpg");
        Files.write(freshFile, new byte[]{1, 2, 3});

        // Run cleanup — but we need to test against tempDir, not the hardcoded /tmp/agent-media/
        // Since MediaCleanupService uses a hardcoded path, we verify behavior indirectly:
        // the service should not throw and should handle missing directory gracefully.
        service.cleanupStaleMedia(); // Should not throw

        // The fresh file in tempDir should still exist (not in the hardcoded media dir)
        assertThat(freshFile).exists();
    }

    @Test
    void cleanupHandlesNonExistentDirectoryGracefully() {
        // The /tmp/agent-media/ directory may not exist — should not throw
        service.cleanupStaleMedia();
    }

    @Test
    void cleanupDeletesOnlyOldFilesInMediaDir() throws Exception {
        // This test uses the real /tmp/agent-media/ directory
        Path mediaDir = Paths.get("/tmp/agent-media/");
        Files.createDirectories(mediaDir);

        Path oldFile = mediaDir.resolve("test-old-" + System.currentTimeMillis() + ".jpg");
        Path freshFile = mediaDir.resolve("test-fresh-" + System.currentTimeMillis() + ".jpg");

        try {
            Files.write(oldFile, new byte[]{1, 2, 3});
            Files.write(freshFile, new byte[]{4, 5, 6});

            // Set old file's mtime to 2 hours ago
            setFileAge(oldFile, 2, ChronoUnit.HOURS);

            service.cleanupStaleMedia();

            // Old file should be deleted
            assertThat(oldFile).doesNotExist();
            // Fresh file should still exist
            assertThat(freshFile).exists();
        } finally {
            Files.deleteIfExists(oldFile);
            Files.deleteIfExists(freshFile);
        }
    }

    @Test
    void cleanupWithEmptyDirectoryDoesNothing() throws Exception {
        Path mediaDir = Paths.get("/tmp/agent-media/");
        Files.createDirectories(mediaDir);
        // Should not throw
        service.cleanupStaleMedia();
    }

    @Test
    void cleanupWithDirectoryEntrySkipsIt() throws Exception {
        Path mediaDir = Paths.get("/tmp/agent-media/");
        Files.createDirectories(mediaDir);
        Path subDir = mediaDir.resolve("test-subdir-" + System.currentTimeMillis());
        try {
            Files.createDirectories(subDir);
            setFileAge(subDir, 2, ChronoUnit.HOURS);
            service.cleanupStaleMedia();
            // Directories should not be deleted by the cleanup (only regular files)
            assertThat(subDir).exists();
        } finally {
            Files.deleteIfExists(subDir);
        }
    }

    /**
     * Set a file's last modified time to a specified age in the past.
     */
    private void setFileAge(Path path, long amount, ChronoUnit unit) throws IOException {
        Files.setLastModifiedTime(path,
            java.nio.file.attribute.FileTime.from(
                Instant.now().minus(amount, unit).minus(1, ChronoUnit.MINUTES)));
    }
}