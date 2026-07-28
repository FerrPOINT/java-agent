package com.azhukov.agent.tools.browser;

import java.util.Map;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChromiumAutoStartTest {

    @Test
    void disabledWhenAutoStartFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(false);

        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper());
        autoStart.start();

        verifyNoInteractions(launcher);
        assertThat(autoStart.getCdpUrl()).isEqualTo("http://localhost:9222");
    }

    @Test
    void usesExternalCdpUrlWhenProvided() {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(true);
        properties.getBrowser().setCdpUrl("http://chrome:9222");

        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper());
        autoStart.start();

        verifyNoInteractions(launcher);
        assertThat(autoStart.getCdpUrl()).isEqualTo("http://chrome:9222");
    }

    @Test
    void doesNotDownloadWhenAutoInstallFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(true);
        properties.getChromium().setAutoInstall(false);
        properties.getBrowser().setCdpUrl("http://localhost:9222");

        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        when(launcher.findExecutable(any(), any())).thenReturn(null);

        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper());
        autoStart.start();

        assertThat(autoStart.isRunning()).isFalse();
    }

    @Test
    void startDownloadsAndLaunchesWhenExecutableMissing() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(true);
        properties.getChromium().setAutoInstall(true);
        properties.getBrowser().setCdpUrl("http://localhost:9222");

        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        when(launcher.findExecutable(any(), any())).thenReturn(null, Path.of("/tmp/chrome"));
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(launcher.launch(any())).thenReturn(process);
        when(launcher.waitForCdp(anyInt())).thenReturn(true);

        ChromiumDownloader downloader = mock(ChromiumDownloader.class);
        ChromiumRevisionResolver resolver = mock(ChromiumRevisionResolver.class);
        when(resolver.resolve(any(), any())).thenReturn("123456");
        doNothing().when(resolver).cacheRevision(any(), any(), any());

        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper()) {
            @Override
            ChromiumPlatform.Platform detectPlatform() {
                return ChromiumPlatform.Platform.LINUX_X64;
            }
            @Override
            ChromiumRevisionResolver createRevisionResolver() {
                return resolver;
            }
            @Override
            ChromiumDownloader createDownloader() {
                return downloader;
            }
        };

        autoStart.start();

        verify(downloader).download(any(), any(), any());
        verify(launcher).launch(Path.of("/tmp/chrome"));
        assertThat(autoStart.isRunning()).isTrue();
    }

    @Test
    void handlesLaunchTimeout() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(true);
        properties.getChromium().setAutoInstall(true);
        properties.getBrowser().setCdpUrl("http://localhost:9222");

        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        when(launcher.findExecutable(any(), any())).thenReturn(Path.of("/tmp/chrome"));
        when(launcher.launch(any())).thenReturn(mock(Process.class));
        when(launcher.waitForCdp(anyInt())).thenReturn(false);

        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper()) {
            @Override
            ChromiumPlatform.Platform detectPlatform() {
                return ChromiumPlatform.Platform.LINUX_X64;
            }
            @Override
            ChromiumRevisionResolver createRevisionResolver() {
                ChromiumRevisionResolver resolver = mock(ChromiumRevisionResolver.class);
                when(resolver.resolve(any(), any())).thenReturn("123456");
                return resolver;
            }
        };

        autoStart.start();

        assertThat(autoStart.isRunning()).isFalse();
    }

    @Test
    void stopKillsRunningProcess() {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper());

        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        autoStart.setProcess(process);

        autoStart.stop();

        verify(process).destroy();
    }

    @Test
    void stopDoesNothingWhenNotRunning() {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = mock(ChromiumLauncher.class);
        ChromiumAutoStart autoStart = new ChromiumAutoStart(properties, launcher, new ObjectMapper());

        autoStart.stop();

        assertThat(autoStart.getCdpUrl()).isEqualTo("http://localhost:9222");
    }
}
