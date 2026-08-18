package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.security.Redactor;
import com.azhukov.agent.service.CheckpointManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TerminalTool}.
 * Covers execute() with simple command, timeout, background mode,
 * error output, cwd echo, blocked commands, and empty command.
 *
 * <p>Foreground tests run real {@code bash -c} commands (fast, deterministic).
 * Background and checkpoint paths are mocked to avoid spawning long-lived
 * processes or touching the filesystem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TerminalToolTest {

    @Mock
    private ProcessTool processTool;

    @Mock
    private Redactor redactor;

    @Mock
    private CheckpointManager checkpointManager;

    @Mock
    private InterruptToken interruptToken;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getTerminal().setDefaultTimeoutSeconds(30);
        p.getTerminal().setMaxTimeoutSeconds(300);
        p.getTerminal().setBlockSudo(true);
        // No custom blocked patterns — rely on built-in defaults
        p.getCheckpoints().setEnabled(false);
        return p;
    }

    private TerminalTool newTool(AgentProperties p) {
        return new TerminalTool(processTool, p, redactor, checkpointManager, interruptToken);
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of(), null);
    }

    @BeforeEach
    void stubRedactor() {
        // Make redactor a pass-through by default
        lenient().when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── 1. Simple command — returns stdout ────────────────────────────────

    @Test
    void executeSimpleCommandReturnsOutput() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hello\"}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("hello");
    }

    // ── 2. Timeout — command exceeds timeout → fail ───────────────────────

    @Test
    void executeTimesOutWhenCommandExceedsTimeout() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        // sleep 5 with a 1-second timeout
        ToolResult result = tool.execute(
            "{\"command\":\"sleep 5\",\"timeout\":1}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timed out");
        assertThat(result.error()).contains("1 seconds");
    }

    // ── 3. Background mode — delegates to ProcessTool.spawn ───────────────

    @Test
    void executeBackgroundModeSpawnsProcess() throws Exception {
        AgentProperties p = properties();

        // Create a real ManagedProcess with a mock Process that exits immediately,
        // so TerminalTool can read .id and .pid fields directly.
        java.io.ByteArrayInputStream emptyIn = new java.io.ByteArrayInputStream(new byte[0]);
        java.io.ByteArrayOutputStream emptyOut = new java.io.ByteArrayOutputStream();
        Process mockProcess = org.mockito.Mockito.mock(Process.class);
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.exitValue()).thenReturn(0);
        when(mockProcess.pid()).thenReturn(99999L);
        when(mockProcess.getInputStream()).thenReturn(emptyIn);
        when(mockProcess.getOutputStream()).thenReturn(emptyOut);

        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "proc_abc123", "echo bg", mockProcess, 30);

        // Stub ProcessTool.spawn to return our ManagedProcess
        org.mockito.Mockito.doReturn(mp).when(processTool)
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any());

        TerminalTool tool = newTool(p);
        ToolResult result = tool.execute(
            "{\"command\":\"echo bg\",\"background\":true}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Background process started");
        assertThat(result.content()).contains("session_id: proc_abc123");
        assertThat(result.content()).contains("pid: 99999");

        // Clean up the managed process threads
        mp.destroy();
    }

    // ── 4. Error output — non-zero exit code still returns output ─────────

    @Test
    void executeReturnsErrorOutputOnNonZeroExit() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        // Write to stderr and exit non-zero — output is captured via redirectErrorStream
        ToolResult result = tool.execute(
            "{\"command\":\"echo error_msg_to_stderr 1>&2; exit 7\"}", null, session());

        // The tool returns ok() with the captured output regardless of exit code
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("error_msg_to_stderr");
    }

    // ── 5. CWD echo — workdir is echoed in output ─────────────────────────

    @Test
    void executeEchoesCwdWhenWorkdirProvided() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        // Use /tmp as the workdir — it exists and is a directory
        ToolResult result = tool.execute(
            "{\"command\":\"pwd\",\"workdir\":\"/tmp\"}", null, session());

        assertThat(result.success()).isTrue();
        // The enhancer appends [cwd: /tmp] to the output
        assertThat(result.content()).contains("[cwd: /tmp]");
    }

    // ── 6. Blocked command — rm -rf / → fail ──────────────────────────────

    @Test
    void executeBlocksDangerousCommand() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"rm -rf /\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Blocked");
    }

    // ── 7. Empty command → fail ───────────────────────────────────────────

    @Test
    void executeFailsOnEmptyCommand() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute("{\"command\":\"\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command is required");
    }

    // ── 8. Blocked sudo command ───────────────────────────────────────────

    @Test
    void executeBlocksSudoCommand() {
        AgentProperties p = properties();
        p.getTerminal().setBlockSudo(true);
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"sudo ls\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("sudo");
    }
}