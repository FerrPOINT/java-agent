package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumDownloaderTest {

    @Test
    void unzipExtractsExecutableAndMarksPermission() throws Exception {
        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com");
        Path zip = Files.createTempFile("chromium", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("chrome-linux/chrome"));
            zos.write(new byte[10]);
            zos.closeEntry();
        }
        Path dir = Files.createTempDirectory("extract");
        java.lang.reflect.Method m = ChromiumDownloader.class.getDeclaredMethod("unzip", Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(downloader, zip, dir);
        Path extracted = dir.resolve("chrome-linux/chrome");
        assertThat(Files.exists(extracted)).isTrue();
        assertThat(extracted.toFile().canExecute()).isTrue();
    }
}
