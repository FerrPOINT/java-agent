package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.io.ByteArrayInputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChromiumDownloaderTest {

    @Test
    void isExecutableEntryDetectsChrome() throws Exception {
        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/");
        java.lang.reflect.Method m = ChromiumDownloader.class.getDeclaredMethod("isExecutableEntry", String.class);
        m.setAccessible(true);

        assertThat(m.invoke(downloader, "chrome-linux/chrome")).isEqualTo(true);
        assertThat(m.invoke(downloader, "chrome.exe")).isEqualTo(true);
        assertThat(m.invoke(downloader, "nacl_helper")).isEqualTo(true);
        assertThat(m.invoke(downloader, "README")).isEqualTo(false);
    }

    @Test
    void unzipExtractsArchive() throws Exception {
        Path dir = Files.createTempDirectory("chrome-download");
        Path zip = dir.resolve("chrome.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("chrome-linux/chrome");
            zos.putNextEntry(entry);
            zos.write("#!/bin/sh\n".getBytes());
            zos.closeEntry();
            ZipEntry dirEntry = new ZipEntry("chrome-linux/");
            zos.putNextEntry(dirEntry);
            zos.closeEntry();
        }

        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/");
        java.lang.reflect.Method m = ChromiumDownloader.class.getDeclaredMethod("unzip", Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(downloader, zip, dir);

        Path extracted = dir.resolve("chrome-linux").resolve("chrome");
        assertThat(Files.exists(extracted)).isTrue();
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    @Test
    void archiveUrlFormatted() throws Exception {
        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/");
        java.lang.reflect.Method m = ChromiumDownloader.class.getDeclaredMethod("archiveUrl", ChromiumPlatform.Platform.class, String.class);
        m.setAccessible(true);
        String url = (String) m.invoke(downloader, ChromiumPlatform.Platform.LINUX_X64, "123");
        assertThat(url).isEqualTo("https://example.com/Linux_x64/123/chrome-linux.zip");
    }


    @Test
    void downloadUsesExistingArchiveAndExtractedDir() throws Exception {
        Path dir = Files.createTempDirectory("chrome-download");
        ChromiumPlatform.Platform platform = ChromiumPlatform.Platform.LINUX_X64;
        String revision = "123";
        Path archive = dir.resolve(ChromiumPlatform.archiveName(platform, revision));
        Files.createDirectories(archive.getParent());
        Files.write(archive, "fake".getBytes());
        Path extracted = dir.resolve(platform.archiveFolder());
        Files.createDirectories(extracted);

        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/");
        Path result = downloader.download(platform, revision, dir);

        assertThat(result).isEqualTo(extracted);
    }

    @Test
    void downloadFetchesArchiveOnNon200() throws Exception {
        Path dir = Files.createTempDirectory("chrome-download");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/", client);

        assertThatThrownBy(() -> downloader.download(ChromiumPlatform.Platform.LINUX_X64, "123", dir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to download");
    }

    @Test
    void downloadFetchesAndExtractsArchive() throws Exception {
        Path dir = Files.createTempDirectory("chrome-download");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        byte[] zipBytes = createMinimalZip("chrome-linux/chrome", "#!/bin/sh\n");
        when(response.body()).thenReturn(new ByteArrayInputStream(zipBytes));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com/", client);
        Path result = downloader.download(ChromiumPlatform.Platform.LINUX_X64, "123", dir);

        assertThat(result).isEqualTo(dir.resolve("chrome-linux"));
        assertThat(Files.exists(result.resolve("chrome"))).isTrue();
    }

    private byte[] createMinimalZip(String entryName, String content) throws Exception {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zos.write(content.getBytes());
            zos.closeEntry();
            zos.finish();
            return baos.toByteArray();
        }
    }
}
