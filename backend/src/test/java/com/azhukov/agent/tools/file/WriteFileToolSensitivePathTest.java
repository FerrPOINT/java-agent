package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultFileSafety;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity (agent/file_safety.py build_write_denied_paths/prefixes):
 * write_file must refuse the FULL sensitive denylist — not only the old
 * 4-path mini list. Regression: ~/.pgpass, ~/.git-credentials, /etc/sudoers,
 * ~/.aws/*, ~/.kube/*, ~/.gnupg/*, /etc/systemd/* used to be writable.
 */
class WriteFileToolSensitivePathTest {

    private final WriteFileTool tool = new WriteFileTool(props(), fileSafety());

    @TempDir
    Path home;

    private static AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        return p;
    }

    private static DefaultFileSafety fileSafety() {
        return new DefaultFileSafety(props());
    }

    private ToolResult write(String path) {
        return tool.execute("{\"path\":\"" + path + "\",\"content\":\"x\"}",
            null, Session.create("u", "p", "m"));
    }

    @Test
    void blocksPostgresPgpass() {
        assertThat(write(home.resolve(".pgpass").toString()).success()).isFalse();
    }

    @Test
    void blocksGitCredentials() {
        assertThat(write(home.resolve(".git-credentials").toString()).success()).isFalse();
    }

    @Test
    void blocksEtcSudoers() {
        assertThat(write("/etc/sudoers").success()).isFalse();
    }

    @Test
    void blocksAwsDirectoryPrefix() {
        assertThat(write(home.resolve(".aws/credentials").toString()).success()).isFalse();
    }

    @Test
    void blocksKubeConfig() {
        assertThat(write(home.resolve(".kube/config").toString()).success()).isFalse();
    }

    @Test
    void blocksGnupgPrefix() {
        assertThat(write(home.resolve(".gnupg/pubring.kbx").toString()).success()).isFalse();
    }

    @Test
    void blocksEtcSystemdPrefix() {
        assertThat(write("/etc/systemd/system/evil.service").success()).isFalse();
    }

    @Test
    void blocksEnvFile() {
        assertThat(write(home.resolve(".env").toString()).success()).isFalse();
    }

    @Test
    void allowsNormalWrite() throws Exception {
        Path f = home.resolve("docs/readme.md");
        ToolResult r = write(f.toString());
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(f)).isEqualTo("x");
    }
}
