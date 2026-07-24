package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public class ChromiumRevisionResolver {

    private static final Logger log = LoggerFactory.getLogger(ChromiumRevisionResolver.class);

    // Known good Chromium revisions per platform (synced periodically). Fallbacks.
    private static final Map<ChromiumPlatform.Platform, String> FALLBACK_REVISIONS = Map.of(
        ChromiumPlatform.Platform.LINUX_X64, "1667635",
        ChromiumPlatform.Platform.MAC_ARM64, "1667635",
        ChromiumPlatform.Platform.MAC_X64, "1667635",
        ChromiumPlatform.Platform.WIN_X64, "1667635"
    );

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ChromiumRevisionResolver(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public String resolve(ChromiumPlatform.Platform platform, String explicitRevision) {
        if (explicitRevision != null && !explicitRevision.isBlank()) {
            return explicitRevision;
        }
        String fromCache = readCachedRevision(platform);
        if (fromCache != null) {
            log.info("Using cached Chromium revision {} for {}", fromCache, platform);
            return fromCache;
        }
        String fallback = FALLBACK_REVISIONS.get(platform);
        if (fallback != null) {
            log.info("Using fallback Chromium revision {} for {}", fallback, platform);
            return fallback;
        }
        throw new IllegalStateException("No Chromium revision available for platform " + platform);
    }

    public void cacheRevision(ChromiumPlatform.Platform platform, String revision, Path cacheDir) {
        try {
            Path dir = cacheDir.resolve(platform.name().toLowerCase());
            Files.createDirectories(dir);
            Path file = dir.resolve(".revision");
            Files.writeString(file, revision);
        } catch (IOException e) {
            log.warn("Failed to cache Chromium revision: {}", e.getMessage());
        }
    }

    private String readCachedRevision(ChromiumPlatform.Platform platform) {
        try {
            Path home = chromiumHome();
            Path file = home.resolve(".revision-" + platform.name().toLowerCase());
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
        } catch (IOException e) {
            log.debug("No cached revision found");
        }
        return null;
    }

    private Path chromiumHome() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".azhukov-agent", "chromium");
    }
}
