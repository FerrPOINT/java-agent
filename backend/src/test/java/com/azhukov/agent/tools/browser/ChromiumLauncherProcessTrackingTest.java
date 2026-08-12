package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M23: Test that ChromiumLauncher tracks the launched process and
 * has a @PreDestroy method to kill it on shutdown.
 */
class ChromiumLauncherProcessTrackingTest {

    @Test
    void hasLaunchedProcessField() throws Exception {
        Field field = ChromiumLauncher.class.getDeclaredField("launchedProcess");
        assertThat(field.getType()).isEqualTo(Process.class);
        // Should be volatile for visibility across threads
        assertThat(java.lang.reflect.Modifier.isVolatile(field.getModifiers())).isTrue();
    }

    @Test
    void hasPreDestroyMethod() throws Exception {
        Method destroyMethod = ChromiumLauncher.class.getDeclaredMethod("destroy");
        assertThat(destroyMethod).isNotNull();
        assertThat(destroyMethod.isAnnotationPresent(jakarta.annotation.PreDestroy.class)).isTrue();
    }

    @Test
    void destroyDoesNotThrowWhenNoProcessLaunched() {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        // Should not throw NPE when no process was launched
        launcher.destroy();
    }

    @Test
    void destroyKillsAliveProcess() throws Exception {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);

        // Start a dummy process
        ProcessBuilder pb = new ProcessBuilder("sleep", "30");
        Process process = pb.start();

        // Set the launchedProcess field
        Field field = ChromiumLauncher.class.getDeclaredField("launchedProcess");
        field.setAccessible(true);
        field.set(launcher, process);

        assertThat(process.isAlive()).isTrue();

        // Call destroy
        launcher.destroy();

        // destroyForcibly is async — wait for the process to die
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();
    }
}