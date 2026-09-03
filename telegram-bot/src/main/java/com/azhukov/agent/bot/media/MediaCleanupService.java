package com.azhukov.agent.bot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Scheduled cleanup for the agent media temp directory.
 *
 * <p>Runs every 30 minutes and deletes files older than 1 hour to prevent
 * the temporary media storage from growing unbounded. Downloaded media
 * (photos, documents, voice messages) are stored here by
 * {@link InboundMediaHandler} so that vision/analysis tools can access them;
 * once the agent turn is complete the files are no longer needed.
 */
@Service
@Slf4j
public class MediaCleanupService {

    private static final Path MEDIA_DIR = AgentMediaPaths.mediaDir();
    private static final Duration MAX_AGE = Duration.ofHours(1);

    /**
     * Periodically delete stale files from the media directory.
     * Cron: run every 30 minutes.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000L)
    public void cleanupStaleMedia() {
        if (!Files.isDirectory(MEDIA_DIR)) {
            return;
        }
        Instant cutoff = Instant.now().minus(MAX_AGE);
        int deleted = 0;
        try (Stream<Path> entries = Files.list(MEDIA_DIR)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                try {
                    if (Files.isRegularFile(entry)) {
                        Instant mtime = Files.getLastModifiedTime(entry).toInstant();
                        if (mtime.isBefore(cutoff)) {
                            Files.deleteIfExists(entry);
                            deleted++;
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete stale media file {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Media cleanup failed to list {}: {}", MEDIA_DIR, e.getMessage());
        }
        if (deleted > 0) {
            log.debug("Media cleanup: deleted {} stale file(s) from {}", deleted, MEDIA_DIR);
        }
    }
}
