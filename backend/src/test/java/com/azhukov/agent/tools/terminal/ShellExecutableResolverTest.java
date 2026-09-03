package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShellExecutableResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    void windowsPrefersConfiguredGitBash() throws Exception {
        Path configured = touch(tempDir.resolve("custom").resolve("bash.exe"));
        Path programFilesGit = touch(tempDir.resolve("Program Files").resolve("Git").resolve("bin").resolve("bash.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("HERMES_GIT_BASH_PATH", configured.toString());
        env.put("ProgramFiles", tempDir.resolve("Program Files").toString());

        assertThat(Path.of(ShellExecutableResolver.bash(env, "Windows 11"))).isEqualTo(configured);
        assertThat(programFilesGit).exists();
    }

    @Test
    void windowsPrefersGitForWindowsOverPathBash() throws Exception {
        Path wslBash = touch(tempDir.resolve("Windows").resolve("System32").resolve("bash.exe"));
        Path gitBash = touch(tempDir.resolve("Program Files").resolve("Git").resolve("bin").resolve("bash.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("ProgramFiles", tempDir.resolve("Program Files").toString());
        env.put("Path", wslBash.getParent().toString());

        assertThat(Path.of(ShellExecutableResolver.bash(env, "Windows 11"))).isEqualTo(gitBash);
    }

    @Test
    void nonWindowsUsesPathBashBeforeFallbacks() throws Exception {
        Path bash = touch(tempDir.resolve("bin").resolve("bash"));

        Map<String, String> env = Map.of("PATH", bash.getParent().toString());

        assertThat(Path.of(ShellExecutableResolver.bash(env, "Linux"))).isEqualTo(bash);
    }

    private static Path touch(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        return path;
    }
}
