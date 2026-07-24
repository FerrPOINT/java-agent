package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumRevisionResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesExplicitRevisionWhenProvided() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com", mapper);
        String revision = resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, "12345");
        assertThat(revision).isEqualTo("12345");
    }

    @Test
    void cachesResolvedRevision(@TempDir Path tempDir) throws IOException {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com", mapper);
        Path home = tempDir.resolve("chromium");
        Files.createDirectories(home);

        resolver.cacheRevision(ChromiumPlatform.Platform.LINUX_X64, "12345", home);

        Path cacheFile = home.resolve("linux_x64").resolve(".revision");
        assertThat(cacheFile).exists();
        assertThat(Files.readString(cacheFile)).contains("12345");
    }
}
