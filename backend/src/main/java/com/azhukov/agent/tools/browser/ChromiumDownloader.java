package com.azhukov.agent.tools.browser;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class ChromiumDownloader {

    private final String baseUrl;
    private final HttpClient httpClient;

    public ChromiumDownloader(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build());
    }

    ChromiumDownloader(String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
    }

    public Path download(ChromiumPlatform.Platform platform, String revision, Path installDir) throws IOException, InterruptedException {
        Path archive = installDir.resolve(ChromiumPlatform.archiveName(platform, revision));
        if (Files.exists(archive)) {
            log.info("Archive already exists: {}", archive);
        } else {
            String url = archiveUrl(platform, revision);
            log.info("Downloading Chromium from {}", url);
            Files.createDirectories(installDir);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(600))
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // H13: Wrap response body in try-with-resources BEFORE checking status code
            // to avoid InputStream leak on non-200 responses.
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Failed to download Chromium: HTTP " + response.statusCode() + " from " + url);
                }
                Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Downloaded {} bytes to {}", Files.size(archive), archive);
        }
        Path extracted = installDir.resolve(platform.archiveFolder());
        if (Files.exists(extracted)) {
            log.info("Chromium already extracted at {}", extracted);
            return extracted;
        }
        unzip(archive, installDir);
        log.info("Extracted Chromium to {}", extracted);
        return extracted;
    }

    private String archiveUrl(ChromiumPlatform.Platform platform, String revision) {
        return String.format("%s/%s/%s/%s", baseUrl, platform.snapshotFolder(), revision, ChromiumPlatform.archiveName(platform, revision));
    }

    private void unzip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IOException("Zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    if (isExecutableEntry(entry.getName())) {
                        try {
                            entryPath.toFile().setExecutable(true);
                        } catch (SecurityException e) {
                            log.warn("Could not mark {} as executable: {}", entryPath, e.getMessage());
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private boolean isExecutableEntry(String name) {
        String base = name;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            base = name.substring(slash + 1);
        }
        String lowerBase = base.toLowerCase();
        if (lowerBase.equals("chrome") || lowerBase.equals("chrome.exe") || lowerBase.equals("chromium")
            || lowerBase.equals("chromium.app")) {
            return true;
        }
        return lowerBase.startsWith("chrome_") || lowerBase.startsWith("nacl_") || lowerBase.equals("nacl_helper")
            || lowerBase.equals("nacl_helper_bootstrap") || lowerBase.equals("nacl_bootstrap")
            || lowerBase.equals("chrome_crashpad_handler") || lowerBase.equals("chrome_sandbox")
            || lowerBase.equals("xdg-mime") || lowerBase.equals("xdg-settings");
    }
}
