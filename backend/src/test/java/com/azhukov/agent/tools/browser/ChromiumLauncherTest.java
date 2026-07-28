package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumLauncherTest {

    @Test
    void findExecutableReturnsConfiguredPath() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path temp = Files.createTempFile("chrome", "");
        temp.toFile().setExecutable(true);
        properties.getChromium().setExecutablePath(temp.toString());

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, Path.of("/nonexistent"));

        assertThat(found).isEqualTo(temp);
        Files.deleteIfExists(temp);
    }

    @Test
    void findExecutableFallsBackToInstallDir() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path dir = Files.createTempDirectory("chrome");
        Path exe = dir.resolve("chrome-linux").resolve("chrome");
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        exe.toFile().setExecutable(true);

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, dir);

        assertThat(found).isEqualTo(exe);
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }


    @Test
    void waitForCdpReturnsTrueWhenPortOpen() throws Exception {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            int port = ss.getLocalPort();
            boolean ready = launcher.waitForCdp("127.0.0.1", port, 2);
            assertThat(ready).isTrue();
        }
    }

    @Test
    void waitForCdpReturnsFalseWhenPortClosed() throws Exception {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);

        boolean ready = launcher.waitForCdp("127.0.0.1", 1, 1);
        assertThat(ready).isFalse();
    }
}
