package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.CheckpointManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerminalToolTest {

    @Mock
    private ProcessTool processTool;

    @Mock
    private AgentProperties properties;

    @Mock
    private Redactor redactor;

    @Mock
    private CheckpointManager checkpointManager;

    @Mock
    private InterruptToken interruptToken;

    @InjectMocks
    private TerminalTool tool;

    @Test
    void executesShellCommandAndReturnsOutput() {
        AgentProperties.TerminalProperties terminal = new AgentProperties.TerminalProperties();
        AgentProperties.SecurityProperties security = new AgentProperties.SecurityProperties();
        AgentProperties.CheckpointProperties checkpoints = new AgentProperties.CheckpointProperties();
        security.setBlockedCommands(java.util.List.of());

        lenient().when(properties.getTerminal()).thenReturn(terminal);
        lenient().when(properties.getSecurity()).thenReturn(security);
        lenient().when(properties.getCheckpoints()).thenReturn(checkpoints);
        lenient().when(checkpointManager.isDangerousCommand(any())).thenReturn(false);
        lenient().when(redactor.redact(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = tool.execute("{\"command\":\"echo hello\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualToIgnoringWhitespace("hello");
    }

    @Test
    void startsBackgroundProcessAndReturnsSessionId() throws Exception {
        AgentProperties.TerminalProperties terminal = new AgentProperties.TerminalProperties();
        AgentProperties.SecurityProperties security = new AgentProperties.SecurityProperties();
        AgentProperties.CheckpointProperties checkpoints = new AgentProperties.CheckpointProperties();
        security.setBlockedCommands(java.util.List.of());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_123", "sleep 10", mock(Process.class), 300
        ) {
            @Override
            public String toString() {
                return "proc_123";
            }
        };

        when(processTool.spawn(any(), anyInt())).thenReturn(managed);
        lenient().when(properties.getTerminal()).thenReturn(terminal);
        lenient().when(properties.getSecurity()).thenReturn(security);
        lenient().when(properties.getCheckpoints()).thenReturn(checkpoints);
        lenient().when(checkpointManager.isDangerousCommand(any())).thenReturn(false);

        var result = tool.execute("{\"command\":\"sleep 10\",\"background\":true}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Background process started");
        assertThat(result.content()).contains("session_id: proc_123");
        assertThat(result.content()).contains("pid:");
    }

    @Test
    void failsWhenCommandMissing() {
        var result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command is required");
    }

    @Test
    void blocksDangerousCommand() {
        AgentProperties.TerminalProperties terminal = new AgentProperties.TerminalProperties();
        AgentProperties.SecurityProperties security = new AgentProperties.SecurityProperties();
        AgentProperties.CheckpointProperties checkpoints = new AgentProperties.CheckpointProperties();
        security.setBlockedCommands(java.util.List.of());

        lenient().when(properties.getTerminal()).thenReturn(terminal);
        lenient().when(properties.getSecurity()).thenReturn(security);
        lenient().when(properties.getCheckpoints()).thenReturn(checkpoints);
        lenient().when(checkpointManager.isDangerousCommand(any())).thenReturn(false);
        lenient().when(redactor.redact(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = tool.execute("{\"command\":\"rm -rf /\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Blocked");
    }
}
