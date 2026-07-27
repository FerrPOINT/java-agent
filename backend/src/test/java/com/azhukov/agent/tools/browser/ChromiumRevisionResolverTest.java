package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumRevisionResolverTest {

    @Test
    void resolveUsesExplicitRevision() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com", new ObjectMapper());
        assertThat(resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, "123")).isEqualTo("123");
    }

    @Test
    void resolveUsesFallbackWhenNoExplicitOrCache() {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com", new ObjectMapper());
        String r = resolver.resolve(ChromiumPlatform.Platform.LINUX_X64, null);
        assertThat(r).isNotBlank();
    }

    @Test
    void cacheRevisionWritesFile() throws Exception {
        ChromiumRevisionResolver resolver = new ChromiumRevisionResolver("https://example.com", new ObjectMapper());
        Path dir = Files.createTempDirectory("chr");
        resolver.cacheRevision(ChromiumPlatform.Platform.LINUX_X64, "rev1", dir);
        Path expected = dir.resolve("linux_x64").resolve(".revision");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.readString(expected)).isEqualTo("rev1");
    }
}
