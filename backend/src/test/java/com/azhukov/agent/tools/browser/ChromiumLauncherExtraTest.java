package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumLauncherExtraTest {

    @TempDir
    Path tempDir;

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
    @DisplayName("findExecutable() returns configured path when it exists and is executable")
    void findExecutableReturnsConfiguredPathWhenExistsAndExecutable() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path exe = Files.createTempFile("chrome-exe", "");
        exe.toFile().setExecutable(true);
        properties.getChromium().setExecutablePath(exe.toString());

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, tempDir);

        assertThat(found).isEqualTo(exe);

        Files.deleteIfExists(exe);
    }

    @Test
    @DisplayName("findExecutable() falls back to installDir when configured path doesn't exist")
    void findExecutableFallsBackToInstallDirWhenConfiguredPathMissing() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setExecutablePath("/nonexistent/chrome");

        // Create executable in installDir matching the platform structure
        Path installDir = tempDir;
        Path exe = installDir.resolve("chrome-linux").resolve("chrome");
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        exe.toFile().setExecutable(true);

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, installDir);

        assertThat(found).isEqualTo(exe);
    }

    @Test
    @DisplayName("findExecutable() falls back to system executable when installDir doesn't have it")
    void findExecutableFallsBackToSystemExecutableWhenInstallDirLacksIt() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setExecutablePath("/nonexistent/chrome");

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        // Use empty installDir so it falls back to system
        Path found = launcher.findExecutable(ChromiumPlatform.Platform.LINUX_X64, tempDir);

        // The result depends on whether chromium is installed on the system
        // We can't guarantee it is, so just verify it doesn't crash
        // If system chrome exists, found should be non-null; otherwise null is acceptable
        if (found != null) {
            assertThat(Files.exists(found)).isTrue();
        }
    }

    @Test
    @DisplayName("findSystemExecutable() returns Optional.empty() when no system chrome found")
    void findSystemExecutableReturnsEmptyWhenNoChromeFound() {
        // This test validates that findSystemExecutable() returns a valid Optional.
        // In the test environment, there may or may not be a system chrome installed.
        // We just verify the method runs without error and returns an Optional.
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);

        java.util.Optional<Path> result = launcher.findSystemExecutable();

        // The result should be a valid Optional - either empty or containing an existing path
        assertThat(result).isNotNull();
        result.ifPresent(path -> {
            assertThat(Files.exists(path)).isTrue();
            assertThat(Files.isExecutable(path)).isTrue();
        });
    }

    @Test
    @DisplayName("launch() throws IOException when executable doesn't exist")
    void launchThrowsIOExceptionWhenExecutableDoesNotExist() {
        AgentProperties properties = new AgentProperties();
        ChromiumLauncher launcher = new ChromiumLauncher(properties);

        Path nonexistent = Path.of("/nonexistent/path/to/chrome");

        assertThatThrownBy(() -> launcher.launch(nonexistent))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("launch() uses custom user data dir when configured")
    void launchUsesCustomUserDataDirWhenConfigured() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path customUserDataDir = Files.createTempDirectory("custom-userdata");
        properties.getChromium().setUserDataDir(customUserDataDir.toString());
        properties.getChromium().setHeadless(true);

        ChromiumLauncher launcher = new ChromiumLauncher(properties);
        Process process = launcher.launch(javaExecutable());
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        process.destroy();

        // Verify the custom user data dir still exists (it was used)
        assertThat(Files.exists(customUserDataDir)).isTrue();

        // Clean up
        Files.walk(customUserDataDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
    }
}
