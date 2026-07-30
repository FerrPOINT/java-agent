package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "RUN_LIVE_CHROMIUM_TEST", matches = "true")
@Tag("live")
class ChromiumAutoStartLiveTest {

    @Test
    void chromiumAutoStartsAndExposesCdp() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getChromium().setAutoStart(true);
        props.getChromium().setAutoInstall(true);
        props.getChromium().setRevision("1667635");
        props.getChromium().setExecutablePath("/nonexistent/chrome");
        props.getBrowser().setCdpUrl("http://localhost:9222");

        ChromiumLauncher launcher = new ChromiumLauncher(props);
        ChromiumAutoStart autoStart = new ChromiumAutoStart(props, launcher, new ObjectMapper());
        autoStart.init();
        autoStart.start();

        try {
            assertThat(autoStart.isRunning()).isTrue();
            assertThat(autoStart.getCdpUrl()).isEqualTo("http://localhost:9222");
        } finally {
            autoStart.stop();
        }
    }
}
