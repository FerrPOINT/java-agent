package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.CheckpointManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("slow")
class TerminalToolExtraTest {

    private CheckpointManager mockCheckpointManager() {
        return mock(CheckpointManager.class);
    }

    private InterruptToken interruptToken() {
        return new InterruptToken();
    }

    @Test
    void rejectsEmptyCommand() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        ToolResult r = tool.execute("{\"command\":\"\"}", null, Session.create("u","noop",""));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Command is required");
    }

    @Test
    void blocksDangerousPattern() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        ToolResult r = tool.execute("{\"command\":\"rm -rf /\"}", null, Session.create("u","noop",""));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Blocked");
    }

    @Test
    void runsEchoSuccessfully() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        ToolResult r = tool.execute("{\"command\":\"echo hello\"}", null, Session.create("u","noop",""));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello");
    }

    @Test
    void backgroundSpawnReturnsSessionId() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        ProcessTool processTool = new ProcessTool();
        TerminalTool tool = new TerminalTool(processTool, props, redactor, mockCheckpointManager(), interruptToken());
        ToolResult r = tool.execute("{\"command\":\"sleep 60\",\"background\":true,\"timeout\":2}", null, Session.create("u","noop",""));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("session_id:");
        processTool.cleanup();
    }

    @Test
    void commandTimesOut() {
        AgentProperties props = new AgentProperties();
        props.getTerminal().setDefaultTimeoutSeconds(1);
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        ToolResult r = tool.execute("{\"command\":\"sleep 10\"}", null, Session.create("u","noop",""));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("timed out");
    }

    @Test
    void interruptMidExecutionKillsProcess() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getTerminal().setDefaultTimeoutSeconds(30);
        Redactor redactor = new DefaultRedactor(props);
        InterruptToken token = new InterruptToken();
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), token);

        Session session = Session.create("u", "noop", "");

        // Run in a separate thread so we can interrupt mid-execution
        java.util.concurrent.CompletableFuture<ToolResult> future =
            java.util.concurrent.CompletableFuture.supplyAsync(() ->
                tool.execute("{\"command\":\"sleep 10\"}", null, session));

        // Give the command time to start
        Thread.sleep(1000);

        // Interrupt
        token.cancel(session.id());

        ToolResult r = future.get(35, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Interrupted by user");
    }

    @Test
    void ptyModeRunsCommandInPseudoTerminal() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        // 'tty' command in PTY mode should report /dev/pts/N instead of "not a tty"
        ToolResult r = tool.execute("{\"command\":\"tty\",\"pty\":true}", null, Session.create("u","noop",""));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("/dev/pts/");
    }

    @Test
    void ptyModeNormalizesCarriageReturns() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        // PTY output has \r\n — verify it's normalized to \n
        ToolResult r = tool.execute("{\"command\":\"echo hello\",\"pty\":true}", null, Session.create("u","noop",""));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello");
        assertThat(r.content()).doesNotContain("\r");
    }

    @Test
    void ptyFalseUsesBashDirectly() {
        AgentProperties props = new AgentProperties();
        Redactor redactor = new DefaultRedactor(props);
        TerminalTool tool = new TerminalTool(null, props, redactor, mockCheckpointManager(), interruptToken());
        // Without pty, 'tty' should report "not a tty"
        ToolResult r = tool.execute("{\"command\":\"tty\",\"pty\":false}", null, Session.create("u","noop",""));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not a tty");
    }
}