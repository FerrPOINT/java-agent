package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void findsExecutableInInstallDir() throws IOException {
        AgentProperties props = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(props);

        Path installDir = tempDir.resolve("install");
        Path archiveDir = installDir.resolve("chrome-linux");
        Path chrome = archiveDir.resolve("chrome");
        Files.createDirectories(archiveDir);
        Files.createFile(chrome);
        chrome.toFile().setExecutable(true);

        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, installDir);
        assertThat(found).isEqualTo(chrome);
    }

    @Test
    void prefersConfiguredExecutable() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getChromium().setExecutablePath(tempDir.resolve("custom-chrome").toString());
        ChromiumLauncher launcher = new ChromiumLauncher(props);

        Path custom = tempDir.resolve("custom-chrome");
        Files.createFile(custom);
        custom.toFile().setExecutable(true);

        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, tempDir.resolve("install"));
        assertThat(found).isEqualTo(custom);
    }

    @Test
    void fallsBackToInstallDirWhenConfiguredMissing() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getChromium().setExecutablePath("/nonexistent/chrome");
        ChromiumLauncher launcher = new ChromiumLauncher(props);

        Path installDir = tempDir.resolve("install");
        Path archiveDir = installDir.resolve("chrome-linux");
        Path chrome = archiveDir.resolve("chrome");
        Files.createDirectories(archiveDir);
        Files.createFile(chrome);
        chrome.toFile().setExecutable(true);

        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, installDir);
        assertThat(found).isEqualTo(chrome);
    }
}
