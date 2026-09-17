package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.RunControlScope;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.CheckpointManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute("{", null, session());

        assertThat(result.success()).isFalse();
        JsonNode json = jsonContent(result);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(result.error()).isEqualTo(json.path("error").asText());
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
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any(), any(), any(), any());

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

    @Test
    void foregroundNotifyModifierFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hi\",\"notify\":true}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("notify only applies to background commands");
    }

    @Test
    void foregroundWatchPatternsModifierFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hi\",\"watch_patterns\":[\"ready\"]}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("notify only applies to background commands");
    }

    @Test
    void foregroundPtyModifierFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hi\",\"pty\":true}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("pty requires background=true");
    }

    @Test
    void explicitZeroTimeoutFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hi\",\"timeout\":0}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout must be a positive number");
    }

    @Test
    void negativeTimeoutFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo hi\",\"timeout\":-1}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout must be a positive number");
    }

    @Test
    void backgroundNotifyTrueMapsToNotifyOnComplete() throws Exception {
        AgentProperties p = properties();
        Process mockProcess = immediateExitedProcess();
        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "proc_notify", "echo bg", mockProcess, 30);
        org.mockito.Mockito.doReturn(mp).when(processTool)
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any(), any(), any(), any());

        TerminalTool tool = newTool(p);
        ToolResult result = tool.execute(
            "{\"command\":\"echo bg\",\"background\":true,\"notify\":true}", null, session());

        assertThat(result.success()).as("error=%s content=%s", result.error(), result.content()).isTrue();
        assertThat(result.content()).contains("notify_on_complete: enabled");
        assertThat(result.content()).doesNotContain("runs SILENTLY");

        mp.destroy();
    }

    @Test
    void backgroundNotifyListMapsToWatchPatterns() throws Exception {
        AgentProperties p = properties();
        Process mockProcess = immediateExitedProcess();
        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "proc_watch", "echo bg", mockProcess, 30);
        org.mockito.Mockito.doReturn(mp).when(processTool)
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any(), any(), any(), any());

        TerminalTool tool = newTool(p);
        ToolResult result = tool.execute(
            "{\"command\":\"echo bg\",\"background\":true,\"notify\":[\"ready\"]}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("watch_patterns: ready");
        assertThat(result.content()).doesNotContain("notify_on_complete: enabled");

        mp.destroy();
    }

    @Test
    void backgroundInvalidNotifyTypeFails() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo bg\",\"background\":true,\"notify\":\"soon\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("notify must be true/false");
    }

    @Test
    void foregroundShellLevelBackgroundWrapperFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"nohup python server.py\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("shell-level background wrappers");
        assertThat(result.error()).contains("background=true");
    }

    @Test
    void foregroundTrailingAmpFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"python -m http.server &\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Foreground command uses '&' backgrounding");
    }

    @Test
    void foregroundLongLivedServerCommandFailsFast() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"npm run dev\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("long-lived server/watch process");
    }

    @Test
    void quotedAmpDoesNotTriggerBackgroundGuidance() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"echo 'left & right'\"}", null, session());

        assertThat(result.success()).as("error=%s content=%s", result.error(), result.content()).isTrue();
        assertThat(result.content()).contains("left & right");
    }

    @Test
    void backgroundCommandInheritsExportedSessionEnvironment() throws Exception {
        AgentProperties p = properties();
        Process mockProcess = immediateExitedProcess();
        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "proc_env", "printf '%s' \"$JAVA_AGENT_BG_ENV\"", mockProcess, 30);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.doReturn(mp).when(processTool)
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any(), envCaptor.capture(), ownerCaptor.capture(), any());
        TerminalTool tool = newTool(p);
        Session s = session();

        ToolResult export = tool.execute(
            "{\"command\":\"export JAVA_AGENT_BG_ENV=hermes-parity\"}", null, s);
        assertThat(export.success()).isTrue();

        ToolResult started = tool.execute(
            "{\"command\":\"printf '%s' \\\"$JAVA_AGENT_BG_ENV\\\"\",\"background\":true,\"notify\":true}", null, s);
        assertThat(started.success()).isTrue();
        assertThat(envCaptor.getValue()).containsEntry("JAVA_AGENT_BG_ENV", "hermes-parity");
        assertThat(ownerCaptor.getValue()).isEqualTo(s.id());
        mp.destroy();
    }

    @Test
    void backgroundCommandUsesRunControlSessionAsProcessOwner() throws Exception {
        AgentProperties p = properties();
        Process mockProcess = immediateExitedProcess();
        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "proc_owner", "echo bg", mockProcess, 30);
        ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.doReturn(mp).when(processTool)
            .spawn(anyString(), anyInt(), anyBoolean(), any(), any(), any(), ownerCaptor.capture(), any());
        TerminalTool tool = newTool(p);
        Session s = session();
        UUID controlId = UUID.randomUUID();

        ToolResult result = tool.execute(
            "{\"command\":\"echo bg\",\"background\":true}", null,
            RunControlScope.withControlSessionId(s, controlId));

        assertThat(result.success()).isTrue();
        assertThat(ownerCaptor.getValue()).isEqualTo(controlId);
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

        // Hermes parity: non-zero exit is a failure, but the captured output
        // (stdout+stderr) is still delivered so the model can diagnose it.
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("exit 7");
        assertThat(result.content()).contains("error_msg_to_stderr");
    }

    @Test
    void executePreservesNonExitFailureCodeWhenCapturingSessionState() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"command\":\"false\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("exit 1");
    }

    @Test
    void executePersistsCwdAfterNonZeroCommandLikeHermes() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);
        Session s = session();

        ToolResult failed = tool.execute(
            "{\"command\":\"cd /tmp && false\"}", null, s);
        ToolResult next = tool.execute(
            "{\"command\":\"pwd\"}", null, s);

        assertThat(failed.success()).isFalse();
        assertThat(failed.error()).isEqualTo("exit 1");
        assertThat(next.success()).isTrue();
        assertThat(next.content()).contains("/tmp");
    }

    @Test
    void executePersistsExportAfterNonZeroCommandLikeHermes() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);
        Session s = session();

        ToolResult failed = tool.execute(
            "{\"command\":\"export JAVA_AGENT_NONZERO_ENV=kept; false\"}", null, s);
        assertThat(TerminalTool.trackedEnv(s.id())).containsEntry("JAVA_AGENT_NONZERO_ENV", "kept");

        ToolResult next = tool.execute(
            "{\"command\":\"printf '%s' \\\"$JAVA_AGENT_NONZERO_ENV\\\"\"}", null, s);

        assertThat(failed.success()).isFalse();
        assertThat(failed.error()).isEqualTo("exit 1");
        assertThat(next.success()).isTrue();
        assertThat(next.content()).contains("kept");
    }

    private Process immediateExitedProcess() {
        java.io.ByteArrayInputStream emptyIn = new java.io.ByteArrayInputStream(new byte[0]);
        java.io.ByteArrayOutputStream emptyOut = new java.io.ByteArrayOutputStream();
        Process mockProcess = mock(Process.class);
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.exitValue()).thenReturn(0);
        when(mockProcess.pid()).thenReturn(99999L);
        when(mockProcess.getInputStream()).thenReturn(emptyIn);
        when(mockProcess.getOutputStream()).thenReturn(emptyOut);
        return mockProcess;
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
        String expectedWorkdir = TerminalOutputEnhancer.resolveWorkdir("/tmp");
        assertThat(result.content()).contains("[cwd: " + expectedWorkdir + "]");
    }

    @Test
    void executeUsesCronSessionWorkdirWhenCallOmitsWorkdir() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);
        Session cronSession = session().withMetadata(TerminalTool.META_WORKDIR, "/tmp");

        ToolResult result = tool.execute("{\"command\":\"pwd\"}", null, cronSession);

        assertThat(result.success()).isTrue();
        String expectedWorkdir = TerminalOutputEnhancer.resolveWorkdir("/tmp");
        assertThat(result.content()).contains("[cwd: " + expectedWorkdir + "]");
    }

    @Test
    void explicitWorkdirDoesNotPersistIntoSessionCwdLikeHermes() {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);
        Session s = session();

        ToolResult scoped = tool.execute(
            "{\"command\":\"pwd\",\"workdir\":\"/tmp\"}", null, s);
        assertThat(TerminalTool.trackedCwd(s.id())).isNull();

        ToolResult next = tool.execute(
            "{\"command\":\"pwd\"}", null, s);

        assertThat(scoped.success()).isTrue();
        assertThat(next.success()).isTrue();
        assertThat(next.content()).doesNotContain("[cwd: /tmp]");
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
    void executeFailsOnEmptyCommand() throws Exception {
        AgentProperties p = properties();
        TerminalTool tool = newTool(p);

        ToolResult result = tool.execute("{\"command\":\"\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command is required");
        JsonNode json = jsonContent(result);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Command is required");
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
