package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for ProcessTool UX improvement:
 * - h51: unique ID prefix lookup — accept unique ID prefixes for session ID.
 */
@Tag("slow")
class ProcessToolPrefixLookupTest {

    @Test
    void prefixLookupFindsProcess() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(12345L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_abcdef123456", "echo hello", process, 300
        );

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        // Full ID works
        ToolResult fullResult = tool.execute("{\"action\":\"poll\",\"sessionId\":\"proc_abcdef123456\"}", null, null);
        assertThat(fullResult.success()).isTrue();
        assertThat(fullResult.content()).contains("proc_abcdef123456");

        // Prefix works (unique)
        ToolResult prefixResult = tool.execute("{\"action\":\"poll\",\"sessionId\":\"proc_abc\"}", null, null);
        assertThat(prefixResult.success()).isTrue();
        assertThat(prefixResult.content()).contains("proc_abcdef123456");
    }

    @Test
    void prefixLookupLogAction() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(99999L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("line1\nline2\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_xyz789", "echo test", process, 300
        );

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        // Prefix lookup for log action
        ToolResult r = tool.execute("{\"action\":\"log\",\"sessionId\":\"proc_xyz\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("line1");
        assertThat(r.content()).contains("line2");
    }

    @Test
    void prefixLookupKillAction() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(88888L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_killme123", "echo hi", process, 300
        );

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        // Prefix lookup for kill action
        ToolResult r = tool.execute("{\"action\":\"kill\",\"sessionId\":\"proc_kill\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("Killed process proc_killme123");
    }

    @Test
    void ambiguousPrefixReturnsNotFound() throws Exception {
        Process process1 = mock(Process.class);
        when(process1.isAlive()).thenReturn(false);
        when(process1.exitValue()).thenReturn(0);
        when(process1.pid()).thenReturn(1L);
        when(process1.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(process1.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        Process process2 = mock(Process.class);
        when(process2.isAlive()).thenReturn(false);
        when(process2.exitValue()).thenReturn(0);
        when(process2.pid()).thenReturn(2L);
        when(process2.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(process2.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess mp1 = new ProcessTool.ManagedProcess("proc_same1", "echo", process1, 300);
        ProcessTool.ManagedProcess mp2 = new ProcessTool.ManagedProcess("proc_same2", "echo", process2, 300);

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, mp1);
        injectProcess(tool, mp2);

        // "proc_same" is ambiguous — matches both
        ToolResult r = tool.execute("{\"action\":\"poll\",\"sessionId\":\"proc_same\"}", null, null);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Process not found");
    }

    @Test
    void nonExistentPrefixReturnsNotFound() {
        ProcessTool tool = new ProcessTool();
        ToolResult r = tool.execute("{\"action\":\"poll\",\"sessionId\":\"nonexistent\"}", null, null);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Process not found");
    }

    @Test
    void emptySessionIdReturnsNotFound() {
        ProcessTool tool = new ProcessTool();
        ToolResult r = tool.execute("{\"action\":\"poll\",\"sessionId\":\"\"}", null, null);
        assertThat(r.success()).isFalse();
    }

    @Test
    void prefixLookupWriteStdin() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(77777L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(os);

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_write_test", "cat", process, 300
        );

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        // Prefix lookup for write action
        ToolResult r = tool.execute("{\"action\":\"write\",\"sessionId\":\"proc_write\",\"data\":\"hello\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("Data written to stdin");
    }

    @Test
    void prefixLookupCloseStdin() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(66666L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(os);

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_close_me", "cat", process, 300
        );

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        // Prefix lookup for close action
        ToolResult r = tool.execute("{\"action\":\"close\",\"sessionId\":\"proc_close\"}", null, null);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("Stdin closed");
    }

    @SuppressWarnings("unchecked")
    private void injectProcess(ProcessTool tool, ProcessTool.ManagedProcess managed) throws Exception {
        Field processesField = ProcessTool.class.getDeclaredField("processes");
        processesField.setAccessible(true);
        Map<String, ProcessTool.ManagedProcess> processes = (Map<String, ProcessTool.ManagedProcess>) processesField.get(tool);
        processes.put(managed.id, managed);
    }
}