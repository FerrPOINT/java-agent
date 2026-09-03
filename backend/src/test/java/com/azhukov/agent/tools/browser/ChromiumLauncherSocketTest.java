package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumLauncherSocketTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path javaExecutable() {
        if (isWindows()) {
            Path javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
            if (Files.exists(javaw)) {
                return javaw;
            }
        }
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        );
    }

    @Test
    void waitForCdpSucceedsWhenPortAccepts() throws Exception {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            boolean ready = launcher.waitForCdp("127.0.0.1", port, 2);
            assertThat(ready).isTrue();
        }
    }

    @Test
    void waitForCdpTimesOutWhenPortClosed() throws Exception {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
        }
        // port now closed
        boolean ready = launcher.waitForCdp("127.0.0.1", 0, 1);
        assertThat(ready).isFalse();
    }

    @Test
    void launchArgsIncludeRemoteDebuggingPort() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setHeadless(true);
        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Process process = launcher.launch(javaExecutable());
        process.destroy();
        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
    }
}
