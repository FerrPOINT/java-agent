package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsZipAndMarksChromeExecutable() throws IOException, InterruptedException {
        Path installDir = tempDir.resolve("install");
        Path archive = createChromeLinuxZip(installDir.resolve("chrome-linux.zip"));

        ChromiumDownloader downloader = new ChromiumDownloader("https://example.com");
        Path extracted = downloader.download(ChromiumPlatform.Platform.LINUX_X64, "1667635", installDir);

        assertThat(extracted).exists();
        Path chrome = extracted.resolve("chrome");
        assertThat(chrome).exists();
        assertThat(chrome.toFile().canExecute()).isTrue();
    }

    private Path createChromeLinuxZip(Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(destination))) {
            zos.putNextEntry(new ZipEntry("chrome-linux/"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("chrome-linux/chrome"));
            byte[] data = "#!/bin/sh\necho mock chrome".getBytes(StandardCharsets.UTF_8);
            zos.write(data);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("chrome-linux/resources.pak"));
            zos.write(new byte[]{0x01, 0x02});
            zos.closeEntry();
        }
        return destination;
    }
}
