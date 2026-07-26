package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
        assertThat(r.content()).contains("exited");
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
        when(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));
        ToolResult r = tool.execute("{\"action\":\"wait\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("exited");
    }

    @Test
    void killRemovesProcess() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(1L);
        inject("p1", new ProcessTool.ManagedProcess("p1", "cmd", process, 10));
        ToolResult r = tool.execute("{\"action\":\"kill\",\"session_id\":\"p1\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(processes).doesNotContainKey("p1");
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
    }

    @Test
    void unknownActionFails() {
        ToolResult r = tool.execute("{\"action\":\"dance\"}", null, null);
        assertThat(r.success()).isFalse();
    }
}
