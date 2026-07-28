package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumRevisionResolverTest {

    @Test
    void returnsExplicitRevision() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com/", new ObjectMapper());
        assertThat(resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, "999")).isEqualTo("999");
    }

    @Test
    void returnsCachedRevision() throws Exception {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com/", new ObjectMapper()) {
            @Override
            protected Path chromiumHome() {
                try {
                    Path home = Files.createTempDirectory("chrome");
                    Files.writeString(home.resolve(".revision-linux_x64"), "12345");
                    return home;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        assertThat(resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, null)).isEqualTo("12345");
    }

    @Test
    void returnsFallbackRevision() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com/", new ObjectMapper()) {
            @Override
            protected Path chromiumHome() {
                return Path.of("/nonexistent-" + System.nanoTime());
            }
        };
        assertThat(resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, null)).isEqualTo("1667635");
    }

    @Test
    void throwsOnUnsupportedPlatform() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com/", new ObjectMapper()) {
            @Override
            protected Path chromiumHome() {
                return Path.of("/nonexistent-" + System.nanoTime());
            }
        };
        assertThatThrownBy(() -> resolver.resolve(ChromiumPlatform.Platform.UNSUPPORTED, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cacheRevisionWritesFile() throws Exception {
        Path home = Files.createTempDirectory("chrome");
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com/", new ObjectMapper());
        resolver.cacheRevision(ChromiumPlatform.Platform.LINUX_X64, "54321", home);

        Path file = home.resolve("linux_x64").resolve(".revision");
        assertThat(Files.readString(file).trim()).isEqualTo("54321");
        Files.walk(home).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }
}
