package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcessToolActionsTest {

    private ProcessTool tool;
    private Map<String, ProcessTool.ManagedProcess> processes;

    @SuppressWarnings("unchecked")
    private void inject(String id, ProcessTool.ManagedProcess mp) throws Exception {
        processes.put(id, mp);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        tool = new ProcessTool();
        Field f = ProcessTool.class.getDeclaredField("processes");
        f.setAccessible(true);
        processes = (Map<String, ProcessTool.ManagedProcess>) f.get(tool);
    }

    private ProcessTool.ManagedProcess fakeProcess(String id) {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(1L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("out".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return new ProcessTool.ManagedProcess(id, "cmd", process, 10);
    }

    @Test
    void pollReturnsStatus() throws Exception {
        inject("p1", fakeProcess("p1"));
        Thread.sleep(100);
        ToolResult r = tool.execute("{\"action\":\"poll\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("\"status\":\"exited\"");
    }

    @Test
    void logReturnsOutput() throws Exception {
        inject("p1", fakeProcess("p1"));
        Thread.sleep(100);
        ToolResult r = tool.execute("{\"action\":\"log\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("out");
    }

    @Test
    void waitReturnsResult() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(1L);
        when(process.waitFor(anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS))).thenReturn(true);
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));
        ToolResult r = tool.execute("{\"action\":\"wait\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("\"status\":\"exited\"");
    }

    @Test
    void killMarksProcessKilledAndKeepsLogReachable() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(1L);
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));
        ToolResult r = tool.execute("{\"action\":\"kill\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("\"status\":\"killed\"");
        assertThat(processes).containsKey("p1");
    }

    @Test
    void killOwnedByDestroysOnlyProcessesFromThatControlSession() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        Process owned = runningProcess(11L);
        Process other = runningProcess(12L);
        Process alreadyExited = mock(Process.class);
        when(alreadyExited.isAlive()).thenReturn(false);
        when(alreadyExited.exitValue()).thenReturn(0);
        when(alreadyExited.pid()).thenReturn(13L);
        when(alreadyExited.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(alreadyExited.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        inject("owned", new ProcessTool.ManagedProcess("owned", "sleep 30", owned, 10, null, owner));
        inject("other", new ProcessTool.ManagedProcess("other", "sleep 30", other, 10, null, otherOwner));
        inject("exited", new ProcessTool.ManagedProcess("exited", "echo done", alreadyExited, 10, null, owner));

        int killed = tool.killOwnedBy(owner);

        assertThat(killed).isEqualTo(1);
        verify(owned).destroy();
        verify(other, never()).destroy();
        verify(alreadyExited, never()).destroy();

        ToolResult poll = tool.execute("{\"action\":\"poll\",\"session_id\":\"owned\"}", null, null);
        assertThat(poll.success()).isTrue();
        assertThat(poll.content()).contains("\"completion_reason\":\"killed\"");
        assertThat(poll.content()).contains("\"termination_source\":\"api_server_run_stop\"");
    }

    @Test
    void closeStdinWorks() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(1L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(os);
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));
        ToolResult r = tool.execute("{\"action\":\"close\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("\"status\":\"ok\"");
    }

    @Test
    void writeReturnsJsonErrorWhenStdinPipeFails() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(1L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new BrokenOutputStream());
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));

        ToolResult r = tool.execute("{\"action\":\"write\",\"session_id\":\"p1\",\"data\":\"hello\"}", null, null);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("\"status\":\"error\"");
        assertThat(r.content()).contains("Failed to write to stdin");
    }

    @Test
    void unknownActionFails() {
        ToolResult r = tool.execute("{\"action\":\"dance\"}", null, null);
        assertThat(r.success()).isFalse();
    }

    private static class BrokenOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("broken pipe");
        }
    }

    private Process runningProcess(long pid) throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(pid);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.waitFor(anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS))).thenReturn(true);
        return process;
    }
}
