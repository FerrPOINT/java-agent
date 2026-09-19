package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultWorkdirResolver contract: a terminal command without an explicit
 * workdir must never inherit the JVM cwd — in the hardened dev container that
 * is /app, read-only, and relative-path writes die with EROFS. Resolution
 * order: agent.core.working-directory, /workspace, user home, tmpdir; only
 * existing writable directories qualify.
 */
class DefaultWorkdirResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void configuredWorkingDirectoryWinsWhenUsable() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());

        File resolved = DefaultWorkdirResolver.resolveDefaultWorkdir(properties);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getAbsolutePath()).isEqualTo(tempDir.toAbsolutePath().toString());
    }

    @Test
    void containerWorkspaceIsPreferredOverUnusableConfiguredValue() throws IOException {
        // Configure a value that does not exist on this machine: resolver must
        // skip it and fall through to /workspace (exists in CI containers) or,
        // on a plain host without /workspace, to home/tmpdir — never null
        // when any writable directory exists, and never a non-writable dir.
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory("/definitely/not/here");

        File resolved = DefaultWorkdirResolver.resolveDefaultWorkdir(properties);

        assertThat(resolved).isNotNull();
        assertThat(resolved).isDirectory();
        assertThat(resolved).canWrite();
        // Must NOT be the configured-but-missing directory.
        assertThat(resolved.getAbsolutePath()).isNotEqualTo("/definitely/not/here");
    }

    @Test
    void nullPropertiesFallsBackToSystemDefaults() {
        File resolved = DefaultWorkdirResolver.resolveDefaultWorkdir(null);

        // A dev host always has home or tmpdir; /workspace exists in containers.
        assertThat(resolved).isNotNull();
        assertThat(resolved).canWrite();
    }

    @Test
    void nonDirectoryConfiguredPathIsSkipped() throws IOException {
        // A FILE (not a directory) as the configured value is deterministically
        // unusable regardless of privileges (chmod-based tests lie under root).
        Path file = Files.createFile(tempDir.resolve("not-a-dir"));
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(file.toString());

        File resolved = DefaultWorkdirResolver.resolveDefaultWorkdir(properties);

        assertThat(resolved).isNotNull();
        assertThat(resolved).isDirectory();
        assertThat(resolved).canWrite();
        assertThat(resolved.getAbsolutePath()).isNotEqualTo(file.toAbsolutePath().toString());
    }
}
