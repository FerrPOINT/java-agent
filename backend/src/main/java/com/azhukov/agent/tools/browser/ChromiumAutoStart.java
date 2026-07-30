package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChromiumAutoStart {

    private final AgentProperties properties;
    private final ChromiumLauncher launcher;
    private ChromiumRevisionResolver revisionResolver;
    private ChromiumDownloader downloader;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicReference<String> cdpUrl = new AtomicReference<>("http://localhost:9222");



    @PostConstruct
    void init() {
        revisionResolver = createRevisionResolver();
        downloader = createDownloader();
    }
    @PostConstruct
    public void start() {
        AgentProperties.ChromiumProperties chromium = properties.getChromium();
        if (!chromium.isAutoStart()) {
            log.info("Chromium auto-start is disabled.");
            return;
        }
        String existing = properties.getBrowser().getCdpUrl();
        if (existing != null && !existing.equals("http://localhost:9222") && !existing.isBlank()) {
            log.info("Using external Chromium CDP URL: {}", existing);
            cdpUrl.set(existing);
            return;
        }
        try {
            Path home = chromiumHome();
            ChromiumPlatform.Platform platform = ChromiumPlatform.detect();
            if (platform == ChromiumPlatform.Platform.UNSUPPORTED) {
                throw new IllegalStateException("Unsupported platform for Chromium auto-install");
            }
            String revision = revisionResolver.resolve(platform, chromium.getRevision());
            Path installDir = home.resolve(platform.name().toLowerCase()).resolve(revision);

            Path executable = launcher.findExecutable(platform, installDir);
            if (executable == null) {
                if (!chromium.isAutoInstall()) {
                    throw new IllegalStateException("Chromium executable not found and auto-install is disabled");
                }
                log.info("Chromium executable not found, downloading revision {} for {}", revision, platform);
                downloader.download(platform, revision, installDir);
                executable = launcher.findExecutable(platform, installDir);
                if (executable == null) {
                    throw new IllegalStateException("Chromium executable still not found after download: " + installDir);
                }
            }

            revisionResolver.cacheRevision(platform, revision, home);
            Process p = launcher.launch(executable);
            process.set(p);
            if (!launcher.waitForCdp(chromium.getLaunchTimeoutSeconds())) {
                throw new IllegalStateException("Chromium did not expose CDP within timeout");
            }
            cdpUrl.set("http://localhost:9222");
            log.info("Chromium auto-started successfully. CDP URL: {}", cdpUrl.get());
        } catch (Exception e) {
            log.error("Failed to auto-start Chromium: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        Process p = process.getAndSet(null);
        if (p != null && p.isAlive()) {
            log.info("Stopping Chromium process (pid={})", p.pid());
            p.destroy();
            try {
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    public String getCdpUrl() {
        return cdpUrl.get();
    }

    public boolean isRunning() {
        Process p = process.get();
        return p != null && p.isAlive();
    }

        ChromiumPlatform.Platform detectPlatform() {
        return ChromiumPlatform.detect();
    }

    ChromiumRevisionResolver createRevisionResolver() {
        return new ChromiumRevisionResolver(properties.getChromium().getDownloadUrl(), objectMapper);
    }

    ChromiumDownloader createDownloader() {
        return new ChromiumDownloader(properties.getChromium().getDownloadUrl());
    }

    void setProcess(Process p) {
        process.set(p);
    }

    private Path chromiumHome() throws IOException {
        String home = System.getProperty("user.home");
        Path dir = Path.of(home, ".azhukov-agent", "chromium");
        Files.createDirectories(dir);
        return dir;
    }
}