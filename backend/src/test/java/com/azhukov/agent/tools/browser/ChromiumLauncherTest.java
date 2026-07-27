package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumLauncherTest {

    @Test
    void waitForCdpTimesOutWhenNoServer() throws InterruptedException {
        AgentProperties props = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(props);
        assertThat(launcher.waitForCdp("127.0.0.1", 39222, 1)).isFalse();
    }

    @Test
    void findSystemExecutableFindsChrome() {
        AgentProperties props = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(props);
        // On CI there may be no chrome; assert present or empty, not specific
        assertThat(launcher.findSystemExecutable()).isPresent();
    }

    @Test
    void findExecutableFallsBackToInstallDirOrSystem() throws Exception {
        AgentProperties props = new AgentProperties();
        Path tmp = Files.createTempDirectory("install");
        ChromiumLauncher launcher = new ChromiumLauncher(props);
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, tmp);
        assertThat(found).isNotNull();
    }
}
