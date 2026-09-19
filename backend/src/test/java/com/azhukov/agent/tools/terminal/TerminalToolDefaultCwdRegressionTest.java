package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the read-only-rootfs EROFS incident: a terminal command
 * without an explicit workdir must run in the configured default workspace,
 * never the JVM cwd (/app in the container — read-only under the hardened
 * dev stack). Verified end-to-end: a relative-path write through the real
 * tool must succeed and report the writable cwd.
 */
class TerminalToolDefaultCwdRegressionTest {

    @TempDir
    Path workspace;

    @Test
    void commandWithoutWorkdirRunsInWritableWorkspace() throws Exception {
        AgentProperties p = new AgentProperties();
        p.getTerminal().setDefaultTimeoutSeconds(30);
        p.getTerminal().setMaxTimeoutSeconds(300);
        p.getCore().setWorkingDirectory(workspace.toString());
        // Real CheckpointManager with null repos: its checkpoint persistence is
        // disabled in tests, but isDangerousCommand() must work (null manager
        // would NPE in the command guard path).
        com.azhukov.agent.service.CheckpointManager checkpoints =
            new com.azhukov.agent.service.CheckpointManager(
                null, null, p, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        TerminalTool tool = new TerminalTool(
            new ProcessTool(), p,
            new com.azhukov.agent.core.security.Redactor() {
                @Override public String redact(String output) { return output; }
                @Override public String redactEnvVars(String output) { return output; }
            },
            checkpoints, null) {
        };

        // Relative-path write + cwd echo — exactly what died with EROFS before.
        ToolResult result = tool.execute(
            "{\"command\":\"echo ok > probe.txt && cat probe.txt && pwd\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("ok");
        assertThat(result.content()).contains(workspace.toAbsolutePath().toString());
        assertThat(java.nio.file.Files.exists(workspace.resolve("probe.txt"))).isTrue();
        // The write happened in the workspace, not wherever the JVM started.
        assertThat(result.content()).doesNotContain("Read-only file system");
    }
}
