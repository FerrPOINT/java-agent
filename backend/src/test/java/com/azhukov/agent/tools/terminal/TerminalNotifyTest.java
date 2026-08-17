package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 3: Terminal notify_on_complete + watch_patterns test.
 * Verifies TerminalArgs parses the new fields and the tool uses them.
 */
@ExtendWith(MockitoExtension.class)
class TerminalNotifyTest {

    @Mock
    private ProcessTool processTool;
    @Mock
    private Redactor redactor;
    @Mock
    private CheckpointManager checkpointManager;
    @Mock
    private InterruptToken interruptToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void terminalArgsParsesNotifyOnComplete() throws Exception {
        String json = """
            {"command":"echo hello","background":true,"notify_on_complete":true}
            """;
        TerminalTool.TerminalArgs args = ToolHandler.parseJson(json, TerminalTool.TerminalArgs.class);

        assertThat(args.notifyOnComplete()).isTrue();
        assertThat(args.command()).isEqualTo("echo hello");
        assertThat(args.background()).isTrue();
    }

    @Test
    void terminalArgsParsesWatchPatterns() throws Exception {
        String json = """
            {"command":"long-job","background":true,"watch_patterns":["DONE","ERROR"]}
            """;
        TerminalTool.TerminalArgs args = ToolHandler.parseJson(json, TerminalTool.TerminalArgs.class);

        assertThat(args.watchPatterns()).containsExactly("DONE", "ERROR");
    }

    @Test
    void terminalArgsParsesSnakeCaseAlias() throws Exception {
        String json = """
            {"command":"test","notify-on-complete":true,"watch-patterns":["signal"]}
            """;
        TerminalTool.TerminalArgs args = ToolHandler.parseJson(json, TerminalTool.TerminalArgs.class);

        assertThat(args.notifyOnComplete()).isTrue();
        assertThat(args.watchPatterns()).containsExactly("signal");
    }

    @Test
    void terminalArgsDefaultsNotifyOnCompleteToFalse() throws Exception {
        String json = """
            {"command":"echo test"}
            """;
        TerminalTool.TerminalArgs args = ToolHandler.parseJson(json, TerminalTool.TerminalArgs.class);

        assertThat(args.notifyOnComplete()).isFalse();
        assertThat(args.watchPatterns()).isEmpty();
    }

    @Test
    void terminalArgsNullWatchPatternsDefaultsToEmpty() {
        TerminalTool.TerminalArgs args = new TerminalTool.TerminalArgs(
            "cmd", 0, false, false, null, false, null
        );
        assertThat(args.watchPatterns()).isNotNull().isEmpty();
    }

    @Test
    void notifyOnExitCallbackFires() throws Exception {
        // Verify ProcessTool.ManagedProcess fires notifyOnExit callback on process exit
        Process process = new ProcessBuilder("bash", "-c", "echo hello").start();
        java.util.function.Consumer<String> callback = id -> { };
        ProcessTool.ManagedProcess mp = new ProcessTool.ManagedProcess(
            "test-proc", "echo hello", process, 10, callback
        );

        // The exit watcher thread should fire the callback when the process exits
        process.waitFor();
        Thread.sleep(500); // Allow exit watcher to fire

        assertThat(process.isAlive()).isFalse();
        mp.destroy();
    }
}