package com.azhukov.agent.tools.media;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * L3 fix: TTL sweeper for generated media artifacts (images/audio).
 * <p>
 * ImageGenTool and TtsTool write successful artifacts into
 * {@code $HERMES_HOME/cache/images} (and explicit user paths). Successful files
 * previously had NO cleanup at all — every generation accumulated forever.
 * Explicit user-supplied paths are NEVER touched (the user owns those); only
 * the tool-managed cache directories are swept.
 * <p>
 * Hermes parity: Hermes cleans its voice-memo/image caches on a schedule;
 * this restores that behaviour for the Java port.
 */
@Slf4j
@Component
public class MediaArtifactCleanup {

    /** Artifacts older than this are deleted. Configurable via agent.media.artifact-ttl-hours. */
    private final Duration ttl;
    private volatile boolean running = true;

    public MediaArtifactCleanup(org.springframework.core.env.Environment env) {
        // env may be null in unit tests — default TTL then.
        if (env != null) {
            String hours = env.getProperty("agent.media.artifact-ttl-hours", "24");
            this.ttl = Duration.ofHours(Math.max(1, Long.parseLong(hours)));
        } else {
            this.ttl = Duration.ofHours(24);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void sweep() {
        if (!running) {
            return;
        }
        Path base = cacheRoot();
        if (base == null || !Files.isDirectory(base)) {
            return;
        }
        Instant cutoff = Instant.now().minus(ttl);
        try (Stream<Path> dirs = Files.list(base)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> sweepDir(dir, cutoff));
        } catch (IOException e) {
            log.debug("Media artifact sweep failed on {}: {}", base, e.getMessage());
        }
    }

    private void sweepDir(Path dir, Instant cutoff) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .sorted(Comparator.naturalOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                        log.debug("Expired media artifact deleted: {}", p);
                    } catch (IOException e) {
                        log.debug("Failed to delete expired media artifact {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.debug("Media artifact sweep failed on {}: {}", dir, e.getMessage());
        }
    }

    Path cacheRoot() {
        String hermesHome = System.getenv("HERMES_HOME");
        if (hermesHome != null && !hermesHome.isBlank()) {
            return Path.of(hermesHome).toAbsolutePath().normalize().resolve("cache");
        }
        return Path.of(System.getProperty("user.home"), ".hermes", "cache").toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        running = false;
    }
}
