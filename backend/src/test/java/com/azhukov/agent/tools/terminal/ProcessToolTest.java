package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
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

@Tag("slow")
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

        // Wait for the reader thread to drain the mocked input stream (deterministic sync)
        waitForReaderThread(managed);

        assertThat(managed.getOutput()).contains(expectedOutput);

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        ToolResult listResult = tool.execute("{\"action\":\"list\"}", null, null);
        assertThat(listResult.success()).isTrue();
        assertThat(listResult.content()).contains("exited");
        assertThat(listResult.content()).contains("proc_test");
        assertThat(listResult.content()).contains("\"pid\":12345");

        ToolResult logResult = tool.execute("{\"action\":\"log\",\"sessionId\":\"proc_test\"}", null, null);
        assertThat(logResult.success()).isTrue();
        assertThat(logResult.content()).contains("\"output\":\"line1\\nline2\"");

        ToolResult pollResult = tool.execute("{\"action\":\"poll\",\"sessionId\":\"proc_test\"}", null, null);
        assertThat(pollResult.success()).isTrue();
        assertThat(pollResult.content()).contains("\"session_id\":\"proc_test\"");
        assertThat(pollResult.content()).contains("\"status\":\"exited\"");
        assertThat(pollResult.content()).contains("\"exit_code\":0");
    }

    @Test
    void pollStripsAnsiAndRedactsOutputAndCommand() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        when(process.pid()).thenReturn(12345L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(
            "\u001B[31mSECRET_VALUE\u001B[0m\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_secret", "echo SECRET_VALUE", process, 300
        );
        managed.awaitOutputDrain(1000);

        ProcessTool tool = new ProcessTool(new AgentProperties(), new ReplacingRedactor());
        injectProcess(tool, managed);

        ToolResult result = tool.execute("{\"action\":\"poll\",\"session_id\":\"proc_secret\"}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).doesNotContain("\u001B");
        assertThat(result.content()).doesNotContain("SECRET_VALUE");
        assertThat(result.content()).contains("[REDACTED]");
    }

    @Test
    void pollCapturesPartialOutputWithoutTrailingNewline() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(22222L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("Password:".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_prompt", "read -s password", process, 300
        );
        managed.awaitOutputDrain(1000);

        ProcessTool tool = new ProcessTool();
        injectProcess(tool, managed);

        ToolResult result = tool.execute("{\"action\":\"poll\",\"session_id\":\"proc_prompt\"}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"running\"");
        assertThat(result.content()).contains("Password:");
    }

    private static class ReplacingRedactor implements Redactor {
        @Override
        public String redact(String output) {
            return output == null ? null : output.replace("SECRET_VALUE", "[REDACTED]");
        }

        @Override
        public String redactEnvVars(String output) {
            return redact(output);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectProcess(ProcessTool tool, ProcessTool.ManagedProcess managed) throws Exception {
        Field processesField = ProcessTool.class.getDeclaredField("processes");
        processesField.setAccessible(true);
        Map<String, ProcessTool.ManagedProcess> processes = (Map<String, ProcessTool.ManagedProcess>) processesField.get(tool);
        processes.put(managed.id, managed);
    }

    /** Join the ManagedProcess reader thread so the output buffer is fully populated. */
    private static void waitForReaderThread(ProcessTool.ManagedProcess managed) throws Exception {
        Field readerField = ProcessTool.ManagedProcess.class.getDeclaredField("readerThread");
        readerField.setAccessible(true);
        Thread readerThread = (Thread) readerField.get(managed);
        readerThread.join(5000);
    }
}
