package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessToolTest {

    @Test
    void startsLongProcessAndCapturesOutput() throws Exception {
        String expectedOutput = "line1\nline2";

        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(12345L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream((expectedOutput + "\n").getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_test", "echo start; sleep 1; echo done", process, 300
        );

        assertThat(managed.command).isEqualTo("echo start; sleep 1; echo done");
        assertThat(managed.id).isEqualTo("proc_test");

        // Wait briefly for the reader thread to read the mocked output
        Thread.sleep(100);

        assertThat(managed.getOutput()).isEqualTo(expectedOutput);

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        ToolResult listResult = tool.execute("{\"action\":\"list\"}", null, null);
        assertThat(listResult.success()).isTrue();
        assertThat(listResult.content()).contains("exited");
        assertThat(listResult.content()).contains("proc_test");
        assertThat(listResult.content()).contains("pid=12345");

        ToolResult logResult = tool.execute("{\"action\":\"log\",\"sessionId\":\"proc_test\"}", null, null);
        assertThat(logResult.success()).isTrue();
        assertThat(logResult.content()).isEqualTo(expectedOutput);

        ToolResult pollResult = tool.execute("{\"action\":\"poll\",\"sessionId\":\"proc_test\"}", null, null);
        assertThat(pollResult.success()).isTrue();
        assertThat(pollResult.content()).contains("session_id: proc_test");
        assertThat(pollResult.content()).contains("status: exited");
        assertThat(pollResult.content()).contains("exit_code: 0");
    }

    @SuppressWarnings("unchecked")
    private void injectProcess(ProcessTool tool, ProcessTool.ManagedProcess managed) throws Exception {
        Field processesField = ProcessTool.class.getDeclaredField("processes");
        processesField.setAccessible(true);
        Map<String, ProcessTool.ManagedProcess> processes = (Map<String, ProcessTool.ManagedProcess>) processesField.get(tool);
        processes.put(managed.id, managed);
    }
}
