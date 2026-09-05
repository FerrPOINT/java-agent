package com.azhukov.agent.tools.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** L3 regression: generated media artifacts get TTL cleanup. */
class MediaArtifactCleanupTest {

    @TempDir
    Path tmp;

    @Test
    void sweepsExpiredArtifactsAndKeepsFreshOnes() throws Exception {
        Path images = tmp.resolve("cache/images");
        Files.createDirectories(images);
        Path old = images.resolve("img_old.png");
        Path fresh = images.resolve("img_fresh.png");
        Files.write(old, new byte[]{1});
        Files.write(fresh, new byte[]{2});
        // old: 2h ago; fresh: now. TTL = 1h.
        Files.setLastModifiedTime(old,
            java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        MediaArtifactCleanup cleaner = new MediaArtifactCleanup(Duration.ofHours(1)) {
            @Override
            Path cacheRoot() {
                return tmp.resolve("cache");
            }
        };
        cleaner.sweep();

        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void missingCacheDirIsNoop() {
        MediaArtifactCleanup cleaner = new MediaArtifactCleanup(Duration.ofHours(1));
        // cacheRoot points at the real home; the dir may or may not exist — must not throw
        cleaner.sweep();
    }
}
